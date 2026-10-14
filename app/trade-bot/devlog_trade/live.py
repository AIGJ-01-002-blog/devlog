"""실계좌 주문 (#264). 바이낸스 현물·USDT-M 선물, 업비트 현물에 시장가 주문을 내고, 실제 체결가·수수료로 Fill을 만든다.

되돌릴 수 없는 일이라 기본은 꺼져 있다. 켜는 조건과 안전장치:
- 실거래소(메인넷)는 환경 변수 DEVLOG_TRADE_LIVE=1(설정)과 --live(확인 플래그)가 둘 다 있어야 주문한다. 테스트넷은 --live --testnet.
- 출금 권한이 켜진 키는 주문 전에 거절한다.
- 주문당 금액 상한(--max-order), 주문당 손실 상한(--max-order-loss), 하루 손실 상한(--max-daily-loss, 한국 시간 기준)을 넘으면 새 매수를 멈춘다.
  청산(매도)은 위험을 줄이는 쪽이라 멈추지 않는다.
- 선물 레버리지는 3배까지만 쓴다.
- 키는 환경 변수로만 받는다. 파일·저장소·로그에 쓰지 않는다.

같은 봉을 다시 처리할 때 주문이 두 번 나가지 않도록, 주문 번호(client order id·identifier)를 종목·방향·봉 시각으로 정하고
주문 전에 그 번호로 이미 낸 주문이 있는지 먼저 찾는다.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from decimal import ROUND_DOWN, Decimal
from pathlib import Path
from typing import Callable

from .broker import Fill, PaperBroker, Position

KST = timezone(timedelta(hours=9))
LEVERAGE_CAP = 3
LIVE_ENV = "DEVLOG_TRADE_LIVE"


class LiveOrderError(RuntimeError):
    pass


class LiveRefused(LiveOrderError):
    """켜는 조건·안전장치에 걸려 주문하지 않은 경우"""


# HTTP 한 번: (메서드, 주소, 헤더, 본문) → (상태 코드, JSON). 테스트는 가짜로 바꾼다
Send = Callable[[str, str, dict, bytes | None], tuple[int, object]]


def http_send(method: str, url: str, headers: dict, body: bytes | None, timeout: float = 15) -> tuple[int, object]:
    req = urllib.request.Request(url, data=body, method=method, headers={"Accept": "application/json",
                                                                        "User-Agent": "devlog-trade-bot", **headers})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:  # noqa: S310 (거래소 주문 주소만 부른다)
            status, raw = res.status, res.read()
    except urllib.error.HTTPError as e:  # 거래소는 거절 사유를 JSON 본문에 담는다
        status, raw = e.code, e.read()
    try:
        return status, json.loads(raw.decode("utf-8")) if raw else {}
    except ValueError:
        return status, {"msg": raw[:200].decode("utf-8", "replace")}


@dataclass
class OrderResult:
    order_id: str
    quantity: Decimal  # 체결 수량(수수료로 깎인 코인은 뺀 뒤)
    price: Decimal  # 평균 체결가
    fee: Decimal  # 계좌 통화로 환산한 수수료


def _step(v: Decimal, step: Decimal) -> Decimal:
    return (v / step).to_integral_value(rounding=ROUND_DOWN) * step if step > 0 else v


def _num(v: Decimal) -> str:
    return format(v.normalize(), "f")


def client_order_id(symbol: str, side: str, at: str) -> str:
    """같은 종목·방향·봉이면 늘 같은 번호. 바이낸스는 36자, [A-Za-z0-9_-]만 받는다"""
    return "dl-" + hashlib.sha256(f"{symbol}|{side}|{at}".encode()).hexdigest()[:32]


class Trader:
    """거래소 주문 한 종류. 하위 클래스가 서명·주소·응답 모양을 맞춘다"""

    code = ""
    currency = ""
    key_env: tuple[str, str] = ("", "")

    testnet_key_env: tuple[str, str] = ("", "")

    def __init__(self, key: str, secret: str, testnet: bool = False, send: Send = http_send):
        if not key or not secret:
            names = self.testnet_key_env if testnet and self.testnet_key_env[0] else self.key_env
            raise LiveRefused(f"{names[0]}·{names[1]} 환경 변수에 거래소 키를 넣어 주세요.")
        self.key, self.secret, self.testnet, self.send = key, secret, testnet, send

    @classmethod
    def from_env(cls, testnet: bool, send: Send = http_send) -> "Trader":
        """테스트넷 키는 이름을 따로 둬서 실계좌 키와 섞이지 않게 한다"""
        names = cls.testnet_key_env if testnet and cls.testnet_key_env[0] else cls.key_env
        return cls(os.environ.get(names[0], ""), os.environ.get(names[1], ""), testnet, send)

    def check_key(self) -> None:
        """출금 권한이 있는 키면 LiveRefused"""
        raise NotImplementedError

    def free_cash(self) -> Decimal:
        raise NotImplementedError

    def free_base(self, symbol: str) -> Decimal:
        raise NotImplementedError

    def set_leverage(self, symbol: str, leverage: int) -> None:
        if leverage != 1:
            raise LiveRefused("레버리지는 선물에서만 써요.")

    def market(self, symbol: str, side: str, quantity: Decimal, price: Decimal, cid: str, reduce_only: bool = False) -> OrderResult:
        """시장가 주문. 같은 cid로 이미 낸 주문이 있으면 새로 내지 않고 그 결과를 돌려준다"""
        found = self.find(symbol, cid)
        if found is not None:
            return found
        try:
            self.place(symbol, side, quantity, price, cid, reduce_only)
        except OSError as e:  # 시간 초과 등: 주문이 들어갔는지 모르니 번호로 다시 찾는다
            found = self.find(symbol, cid)
            if found is None:
                raise LiveOrderError(f"주문 결과를 확인하지 못했어요. 거래소 화면에서 {cid} 주문을 확인해 주세요: {e}") from e
            return found
        for _ in range(5):  # 시장가는 바로 체결되지만 조회에 늦게 잡힐 수 있다
            found = self.find(symbol, cid)
            if found is not None:
                return found
            time.sleep(0.5)
        raise LiveOrderError(f"주문은 냈지만 체결을 확인하지 못했어요. 거래소 화면에서 {cid} 주문을 확인해 주세요.")

    def place(self, symbol: str, side: str, quantity: Decimal, price: Decimal, cid: str, reduce_only: bool) -> None:
        raise NotImplementedError

    def find(self, symbol: str, cid: str) -> OrderResult | None:
        """다 체결된 주문만 돌려준다. 없으면 None"""
        raise NotImplementedError

    def _ok(self, status: int, data: object, what: str) -> object:
        if status >= 400 or (isinstance(data, dict) and isinstance(data.get("code"), int) and data["code"] < 0):
            msg = data.get("msg") or data.get("error", {}).get("message") if isinstance(data, dict) else data
            raise LiveOrderError(f"{self.code} {what} 실패 ({status}): {msg or data}")
        return data


class _BinanceBase(Trader):
    currency = "USDT"
    key_env = ("BINANCE_API_KEY", "BINANCE_API_SECRET")
    base_url = ""
    testnet_url = ""
    prefix = ""  # /api/v3 | /fapi/v1

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.url = self.testnet_url if self.testnet else self.base_url
        self._steps: dict[str, Decimal] = {}

    def _signed(self, method: str, path: str, params: dict) -> tuple[int, object]:
        q = {**params, "timestamp": int(time.time() * 1000), "recvWindow": 5000}
        query = urllib.parse.urlencode(q)
        sig = hmac.new(self.secret.encode(), query.encode(), hashlib.sha256).hexdigest()
        return self.send(method, f"{self.url}{path}?{query}&signature={sig}", {"X-MBX-APIKEY": self.key}, None)

    def check_key(self) -> None:
        if self.testnet:  # 테스트넷 키는 출금이 없다. 권한 조회 주소도 없다
            return
        data = self._ok(*self._restrictions(), "키 권한 조회")
        if not isinstance(data, dict) or data.get("enableWithdrawals") is not False:
            raise LiveRefused("출금 권한이 켜진 바이낸스 키예요. 출금을 끈 키를 새로 만들어 주세요.")

    def _restrictions(self) -> tuple[int, object]:
        q = {"timestamp": int(time.time() * 1000), "recvWindow": 5000}
        query = urllib.parse.urlencode(q)
        sig = hmac.new(self.secret.encode(), query.encode(), hashlib.sha256).hexdigest()
        return self.send("GET", f"https://api.binance.com/sapi/v1/account/apiRestrictions?{query}&signature={sig}",
                         {"X-MBX-APIKEY": self.key}, None)

    def step(self, symbol: str) -> Decimal:
        if symbol not in self._steps:
            status, data = self.send("GET", f"{self.url}{self.prefix}/exchangeInfo?symbol={symbol}", {}, None)
            data = self._ok(status, data, "종목 정보 조회")
            info = next((s for s in data.get("symbols", []) if s.get("symbol") == symbol), None)
            if info is None:
                raise LiveOrderError(f"{self.code}에 {symbol} 종목이 없어요.")
            lot = next((f for f in info.get("filters", []) if f.get("filterType") in ("MARKET_LOT_SIZE", "LOT_SIZE")
                        and Decimal(f.get("stepSize", "0")) > 0), {"stepSize": "0.00000001"})
            self._steps[symbol] = Decimal(lot["stepSize"]).normalize()
        return self._steps[symbol]

    def _find(self, symbol: str, cid: str) -> dict | None:
        status, data = self._signed("GET", f"{self.prefix}/order", {"symbol": symbol, "origClientOrderId": cid})
        if status == 400 and isinstance(data, dict) and data.get("code") == -2013:  # 그런 주문 없음
            return None
        data = self._ok(status, data, "주문 조회")
        return data if isinstance(data, dict) and data.get("status") == "FILLED" else None


class BinanceSpotTrader(_BinanceBase):
    code = "BINANCE"
    base_url = "https://api.binance.com"
    testnet_url = "https://testnet.binance.vision"
    testnet_key_env = ("BINANCE_TESTNET_API_KEY", "BINANCE_TESTNET_API_SECRET")
    prefix = "/api/v3"

    def _balances(self) -> dict[str, Decimal]:
        status, data = self._signed("GET", "/api/v3/account", {"omitZeroBalances": "true"})
        data = self._ok(status, data, "잔고 조회")
        return {b["asset"]: Decimal(b["free"]) for b in data.get("balances", [])}

    def free_cash(self) -> Decimal:
        return self._balances().get(self.currency, Decimal(0))

    def free_base(self, symbol: str) -> Decimal:
        return self._balances().get(symbol.removesuffix(self.currency), Decimal(0))

    def place(self, symbol, side, quantity, price, cid, reduce_only) -> None:
        params = {"symbol": symbol, "side": side, "type": "MARKET", "newClientOrderId": cid, "newOrderRespType": "ACK"}
        if side == "BUY":  # 살 때는 쓸 USDT 금액으로 낸다. 수량 단위를 몰라도 되고 잔고를 넘지 않는다
            params["quoteOrderQty"] = _num((quantity * price).quantize(Decimal("0.01"), rounding=ROUND_DOWN))
        else:
            params["quantity"] = _num(_step(quantity, self.step(symbol)))
        self._ok(*self._signed("POST", "/api/v3/order", params), "주문")

    def find(self, symbol, cid) -> OrderResult | None:
        o = self._find(symbol, cid)
        if o is None:
            return None
        qty, quote = Decimal(o["executedQty"]), Decimal(o["cummulativeQuoteQty"])
        price = quote / qty
        status, trades = self._signed("GET", "/api/v3/myTrades", {"symbol": symbol, "orderId": o["orderId"]})
        fee, base = Decimal(0), symbol.removesuffix(self.currency)
        for t in self._ok(status, trades, "체결 조회"):
            c = Decimal(t["commission"])
            if t["commissionAsset"] == self.currency:
                fee += c
            elif t["commissionAsset"] == base:  # 산 코인에서 수수료를 떼면 받은 수량이 줄어든다
                fee += c * price
                qty -= c
            else:  # BNB 할인 등 다른 코인으로 낸 수수료는 금액을 알 수 없어 기록에서 빠진다
                print(f"수수료 {c:f} {t['commissionAsset']}는 {self.currency}로 바꾸지 못해 기록에서 빠져요.", file=sys.stderr)
        return OrderResult(str(o["orderId"]), qty, price, fee.quantize(Decimal("0.00000001")))


class BinanceFuturesTrader(_BinanceBase):
    """USDT-M 무기한 선물. 단방향 포지션 기준: BUY로 롱 진입, SELL(reduceOnly)로 청산"""

    code = "BINANCE_FUTURES"
    base_url = "https://fapi.binance.com"
    testnet_url = "https://testnet.binancefuture.com"
    testnet_key_env = ("BINANCE_FUTURES_TESTNET_API_KEY", "BINANCE_FUTURES_TESTNET_API_SECRET")
    prefix = "/fapi/v1"

    def free_cash(self) -> Decimal:
        status, data = self._signed("GET", "/fapi/v2/balance", {})
        data = self._ok(status, data, "잔고 조회")
        return next((Decimal(b["availableBalance"]) for b in data if b.get("asset") == self.currency), Decimal(0))

    def free_base(self, symbol: str) -> Decimal:
        status, data = self._signed("GET", "/fapi/v2/positionRisk", {"symbol": symbol})
        data = self._ok(status, data, "포지션 조회")
        return sum((Decimal(p["positionAmt"]) for p in data if p.get("symbol") == symbol), Decimal(0))

    def set_leverage(self, symbol: str, leverage: int) -> None:
        if not 1 <= leverage <= LEVERAGE_CAP:
            raise LiveRefused(f"레버리지는 1~{LEVERAGE_CAP}배만 써요.")
        self._ok(*self._signed("POST", "/fapi/v1/leverage", {"symbol": symbol, "leverage": leverage}), "레버리지 설정")

    def place(self, symbol, side, quantity, price, cid, reduce_only) -> None:
        params = {"symbol": symbol, "side": side, "type": "MARKET", "quantity": _num(_step(quantity, self.step(symbol))),
                  "newClientOrderId": cid, "newOrderRespType": "ACK"}
        if reduce_only:
            params["reduceOnly"] = "true"
        self._ok(*self._signed("POST", "/fapi/v1/order", params), "주문")

    def find(self, symbol, cid) -> OrderResult | None:
        o = self._find(symbol, cid)
        if o is None:
            return None
        status, trades = self._signed("GET", "/fapi/v1/userTrades", {"symbol": symbol, "orderId": o["orderId"]})
        fee = sum((Decimal(t["commission"]) for t in self._ok(status, trades, "체결 조회")
                   if t.get("commissionAsset") == self.currency), Decimal(0))
        return OrderResult(str(o["orderId"]), Decimal(o["executedQty"]), Decimal(o["avgPrice"]), fee)


def jwt_hs256(payload: dict, secret: str) -> str:
    def b64(raw: bytes) -> str:
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()
    head = b64(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    body = b64(json.dumps(payload, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), f"{head}.{body}".encode(), hashlib.sha256).digest()
    return f"{head}.{body}.{b64(sig)}"


class UpbitTrader(Trader):
    """업비트 원화 마켓 현물. 테스트넷이 없어 테스트는 가짜 HTTP로만 한다"""

    code = "UPBIT"
    currency = "KRW"
    key_env = ("UPBIT_ACCESS_KEY", "UPBIT_SECRET_KEY")
    base_url = "https://api.upbit.com"

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        if self.testnet:
            raise LiveRefused("업비트는 테스트넷이 없어요. --testnet 없이 소액으로 확인해 주세요.")

    def _auth(self, params: dict | None) -> dict:
        claims = {"access_key": self.key, "nonce": str(uuid.uuid4())}
        if params:
            query = urllib.parse.unquote(urllib.parse.urlencode(params, doseq=True))
            claims.update(query_hash=hashlib.sha512(query.encode()).hexdigest(), query_hash_alg="SHA512")
        return {"Authorization": "Bearer " + jwt_hs256(claims, self.secret)}

    def _get(self, path: str, params: dict | None = None) -> tuple[int, object]:
        url = f"{self.base_url}{path}" + (f"?{urllib.parse.urlencode(params)}" if params else "")
        return self.send("GET", url, self._auth(params), None)

    def check_key(self) -> None:
        # 출금 조회가 되는 키면 출금 권한도 열려 있을 수 있어 거절한다. 출금 권한이 없는 키는 out_of_scope로 막힌다
        status, data = self._get("/v1/withdraws/chance", {"currency": "BTC", "net_type": "BTC"})
        if status < 400:
            raise LiveRefused("출금 권한이 켜진 업비트 키예요. 자산 조회·주문 조회·주문하기만 고른 키를 새로 만들어 주세요.")

    def _accounts(self) -> dict[str, Decimal]:
        status, data = self._get("/v1/accounts")
        data = self._ok(status, data, "잔고 조회")
        return {a["currency"]: Decimal(a["balance"]) for a in data}

    def free_cash(self) -> Decimal:
        return self._accounts().get(self.currency, Decimal(0))

    def free_base(self, symbol: str) -> Decimal:
        return self._accounts().get(symbol.split("-", 1)[1], Decimal(0))

    def place(self, symbol, side, quantity, price, cid, reduce_only) -> None:
        params = {"market": symbol, "identifier": cid}
        if side == "BUY":  # 시장가 매수는 쓸 원화 금액(price)으로 낸다
            params.update(side="bid", ord_type="price", price=_num((quantity * price).to_integral_value(rounding=ROUND_DOWN)))
        else:
            params.update(side="ask", ord_type="market", volume=_num(_step(quantity, Decimal("0.00000001"))))
        body = json.dumps(params).encode()
        self._ok(*self.send("POST", f"{self.base_url}/v1/orders", {**self._auth(params), "Content-Type": "application/json"},
                            body), "주문")

    def find(self, symbol, cid) -> OrderResult | None:
        status, o = self._get("/v1/order", {"identifier": cid})
        if status == 404:
            return None
        o = self._ok(status, o, "주문 조회")
        # 시장가 매수는 남은 원화 자투리 때문에 cancel로 끝나는 게 정상이다. 체결 수량이 있으면 끝난 주문으로 본다
        if o.get("state") not in ("done", "cancel") or Decimal(o.get("executed_volume") or 0) <= 0:
            return None
        qty = Decimal(o["executed_volume"])
        funds = sum((Decimal(t["funds"]) for t in o.get("trades", [])), Decimal(0))
        return OrderResult(o["uuid"], qty, funds / qty, Decimal(o.get("paid_fee") or 0).quantize(Decimal(1)))


TRADERS: dict[str, type[Trader]] = {"binance": BinanceSpotTrader, "binance-futures": BinanceFuturesTrader, "upbit": UpbitTrader}


@dataclass
class Limits:
    max_order: Decimal  # 한 번 살 때 쓸 수 있는 최대 금액 (계좌 통화)
    max_order_loss: Decimal  # 한 번 청산에서 이만큼 잃으면 새 매수를 멈춘다
    max_daily_loss: Decimal  # 한국 시간 하루 동안 이만큼 잃으면 그날 새 매수를 멈춘다
    leverage: int = 1

    def __post_init__(self):
        for name in ("max_order", "max_order_loss", "max_daily_loss"):
            if getattr(self, name) <= 0:
                raise LiveRefused(f"--{name.replace('_', '-')}에 0보다 큰 금액을 넣어 주세요. 실계좌는 상한 없이 돌지 않아요.")
        if not 1 <= self.leverage <= LEVERAGE_CAP:
            raise LiveRefused(f"레버리지는 1~{LEVERAGE_CAP}배만 써요.")


@dataclass
class LiveBroker(PaperBroker):
    """PaperBroker와 같은 모양(buy·sell·holding·save·load)으로 실계좌에 주문한다.
    보유 수량·평균 단가·실현 손익은 실제 체결로 장부에 남기고, 현금은 실행할 때마다 거래소 잔고로 맞춘다."""

    trader: Trader | None = None
    limits: Limits | None = None
    mode: str = "LIVE"
    day: str = ""  # 하루 손실을 세는 한국 날짜
    day_pnl: Decimal = Decimal(0)
    halted: str = ""  # 비어 있지 않으면 새 매수를 멈춘 이유. python -m devlog_trade.live resume으로 푼다
    log: Callable[[str], None] = field(default=lambda m: print(m, file=sys.stderr))
    shorts: dict[str, Position] = field(default_factory=dict)  # 선물 숏 포지션. 수량은 양수로 둔다

    @property
    def futures(self) -> bool:
        return isinstance(self.trader, BinanceFuturesTrader)

    def _fill(self, r: "OrderResult", symbol, name, side, pnl, reason, at, tag: str) -> Fill:
        """선물은 #260 전까지 #257 모양으로 남긴다: 이름 '비트코인 선물', 이유 앞에 '[선물 숏 진입 3x]'"""
        if self.futures:
            name, reason = f"{name} 선물", f"[선물 {tag} {self.limits.leverage}x] {reason}".rstrip()
        return Fill(f"live-{self.exchange}-{r.order_id}", symbol, name, side, r.quantity, r.price, r.fee, pnl, reason, at,
                    self.exchange, self.currency)

    def _spend(self, quantity: Decimal, price: Decimal, at: str) -> Decimal:
        """새 진입에 쓸 금액. 멈춘 상태면 0"""
        self._count(at, Decimal(0))  # 날이 바뀌었으면 하루 손실 멈춤을 푼다
        if self.halted:
            self.log(f"새 진입을 멈췄어요: {self.halted}")
            return Decimal(0)
        spend = min(quantity * price, self.limits.max_order, self.cash * self.limits.leverage)
        if 0 < spend < quantity * price:
            self.log(f"주문 금액을 상한에 맞춰 {_num(spend)} {self.currency}로 줄였어요.")
        return max(spend, Decimal(0))

    def _closed(self, pnl: Decimal, at: str) -> None:
        self._count(at, pnl)
        if -pnl >= self.limits.max_order_loss and not self.halted:
            self.halted = f"한 번 청산 손실 {_num(-pnl)} {self.currency}가 상한 {_num(self.limits.max_order_loss)}에 닿았어요 ({at})"

    def sync(self) -> None:
        self.cash = self.trader.free_cash()

    def _count(self, at: str, pnl: Decimal) -> None:
        day = datetime.fromisoformat(at.replace("Z", "+00:00")).astimezone(KST).date().isoformat()
        if day != self.day:
            self.day, self.day_pnl = day, Decimal(0)
            if self.halted.startswith("하루"):
                self.halted = ""
        self.day_pnl += pnl
        if -self.day_pnl >= self.limits.max_daily_loss:
            self.halted = f"하루 손실 {_num(-self.day_pnl)} {self.currency}가 상한 {_num(self.limits.max_daily_loss)}에 닿았어요 ({day})"

    def buy(self, symbol, name, quantity, price, reason, at) -> Fill | None:
        spend = self._spend(quantity, price, at)
        if spend <= 0:
            return None
        try:
            r = self.trader.market(symbol, "BUY", spend / price, price, client_order_id(symbol, "BUY", at))
        except LiveOrderError as e:
            self.log(str(e))
            return None
        pos = self.positions.setdefault(symbol, Position())
        total = pos.quantity * pos.avg_price + r.quantity * r.price
        pos.quantity += r.quantity
        pos.avg_price = total / pos.quantity
        self._count(at, -r.fee)
        self.sync()
        return self._fill(r, symbol, name, "BUY", None, reason, at, "롱 진입")

    def sell(self, symbol, name, quantity, price, reason, at) -> Fill | None:
        pos = self.positions.get(symbol)
        if pos is None or quantity <= 0:
            return None
        quantity = min(quantity, pos.quantity, max(self.trader.free_base(symbol), Decimal(0)))  # 수수료로 줄어든 자투리까지 맞춘다
        if quantity <= 0:
            self.log(f"{symbol}을 거래소에서 찾지 못해 팔지 않았어요. 직접 정리했는지 거래소 화면을 확인해 주세요.")
            return None
        try:
            r = self.trader.market(symbol, "SELL", quantity, price, client_order_id(symbol, "SELL", at), reduce_only=True)
        except LiveOrderError as e:
            self.log(str(e))
            return None
        pnl = self._money(r.quantity * r.price - r.quantity * pos.avg_price - r.fee)
        pos.quantity -= r.quantity
        if pos.quantity <= 0 or pos.quantity * r.price < Decimal("0.01"):  # 팔 수 없는 자투리는 장부에서 지운다
            del self.positions[symbol]
        self._closed(pnl, at)
        self.sync()
        return self._fill(r, symbol, name, "SELL", pnl, reason, at, "롱 청산")

    # 선물 숏: 진입은 SELL, 청산은 BUY(reduceOnly). 단방향 포지션 기준이라 같은 종목 롱과 숏을 함께 들지 않는다
    def open_short(self, symbol, name, quantity, price, reason, at) -> Fill | None:
        if not self.futures:
            raise LiveRefused("숏은 binance-futures에서만 해요.")
        if symbol in self.positions:
            self.log(f"{symbol} 롱을 들고 있어 숏에 들어가지 않았어요.")
            return None
        spend = self._spend(quantity, price, at)
        if spend <= 0:
            return None
        try:
            r = self.trader.market(symbol, "SELL", spend / price, price, client_order_id(symbol, "SHORT", at))
        except LiveOrderError as e:
            self.log(str(e))
            return None
        pos = self.shorts.setdefault(symbol, Position())
        total = pos.quantity * pos.avg_price + r.quantity * r.price
        pos.quantity += r.quantity
        pos.avg_price = total / pos.quantity
        self._count(at, -r.fee)
        self.sync()
        return self._fill(r, symbol, name, "SELL", None, reason, at, "숏 진입")

    def close_short(self, symbol, name, quantity, price, reason, at) -> Fill | None:
        pos = self.shorts.get(symbol)
        if pos is None or quantity <= 0:
            return None
        quantity = min(quantity, pos.quantity, max(-self.trader.free_base(symbol), Decimal(0)))
        if quantity <= 0:
            self.log(f"{symbol} 숏을 거래소에서 찾지 못해 청산하지 않았어요. 거래소 화면을 확인해 주세요.")
            return None
        try:
            r = self.trader.market(symbol, "BUY", quantity, price, client_order_id(symbol, "COVER", at), reduce_only=True)
        except LiveOrderError as e:
            self.log(str(e))
            return None
        pnl = self._money(r.quantity * pos.avg_price - r.quantity * r.price - r.fee)
        pos.quantity -= r.quantity
        if pos.quantity <= 0 or pos.quantity * r.price < Decimal("0.01"):
            del self.shorts[symbol]
        self._closed(pnl, at)
        self.sync()
        return self._fill(r, symbol, name, "BUY", pnl, reason, at, "숏 청산")

    def short_holding(self, symbol: str) -> Decimal:
        pos = self.shorts.get(symbol)
        return pos.quantity if pos else Decimal(0)

    def save(self, path: Path) -> None:
        super().save(path)
        data = json.loads(path.read_text(encoding="utf-8"))
        data.update(mode=self.mode, day=self.day, day_pnl=str(self.day_pnl), halted=self.halted,
                    shorts={s: {"quantity": str(p.quantity), "avg_price": str(p.avg_price)} for s, p in self.shorts.items()})
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)

    @classmethod
    def open(cls, path: Path, trader: Trader, limits: Limits) -> "LiveBroker":
        """실계좌 상태 파일을 열거나 새로 만든다. 모의 계좌 파일은 받지 않는다(장부가 섞이지 않게)"""
        b = cls(cash=Decimal(0), trader=trader, limits=limits, currency=trader.currency, exchange=trader.code)
        if path.exists():
            data = json.loads(path.read_text(encoding="utf-8"))
            if data.get("mode") != "LIVE" or data.get("exchange") != trader.code:
                raise LiveRefused(f"{path}은 실계좌 {trader.code} 상태 파일이 아니에요. --state로 다른 파일을 주세요.")
            paper = PaperBroker.load(path)
            b.positions, b.last_date, b.fee_rate = paper.positions, paper.last_date, paper.fee_rate
            b.day, b.day_pnl, b.halted = data.get("day", ""), Decimal(data.get("day_pnl", "0")), data.get("halted", "")
            b.shorts = {s: Position(Decimal(p["quantity"]), Decimal(p["avg_price"])) for s, p in data.get("shorts", {}).items()}
        trader.check_key()
        return b


def require_live(args: argparse.Namespace) -> None:
    """설정(환경 변수)과 확인 플래그(--live)가 둘 다 있어야 실거래소에 주문한다. 테스트넷은 --live만"""
    if not getattr(args, "live", False):
        raise LiveRefused("실계좌 주문은 --live를 줘야 켜져요. 기본은 모의매매예요.")
    if not getattr(args, "testnet", False) and os.environ.get(LIVE_ENV) != "1":
        raise LiveRefused(f"실거래소 주문은 환경 변수 {LIVE_ENV}=1과 --live가 둘 다 있어야 해요. 먼저 --testnet으로 확인해 주세요.")


def open_live(args: argparse.Namespace, market: str, state: Path, send: Send = http_send) -> LiveBroker:
    """__main__의 coin 모드가 부르는 입구. 켜는 조건 → 키 → 출금 권한 → 레버리지 → 잔고 순으로 확인한다"""
    require_live(args)
    limits = Limits(Decimal(args.max_order or 0), Decimal(args.max_order_loss or 0), Decimal(args.max_daily_loss or 0),
                    int(getattr(args, "leverage", 1) or 1))
    trader = TRADERS[market].from_env(bool(getattr(args, "testnet", False)), send)
    broker = LiveBroker.open(state, trader, limits)
    if isinstance(trader, BinanceFuturesTrader):
        trader.set_leverage(args.symbol, limits.leverage)
    elif limits.leverage != 1:
        raise LiveRefused("레버리지는 binance-futures에서만 써요.")
    broker.sync()
    return broker


def add_live_args(p: argparse.ArgumentParser) -> None:
    """coin 명령에 붙일 실계좌 옵션. 기본값이 없어 상한을 직접 정해야 실계좌가 돈다"""
    p.add_argument("--live", action="store_true", help=f"실계좌 주문 확인 플래그. 실거래소는 {LIVE_ENV}=1도 있어야 해요")
    p.add_argument("--testnet", action="store_true", help="거래소 테스트넷에 주문해요 (바이낸스만)")
    p.add_argument("--max-order", help="실계좌: 한 번 살 때 쓸 최대 금액 (계좌 통화)")
    p.add_argument("--max-order-loss", help="실계좌: 한 번 청산 손실이 이만큼이면 새 매수를 멈춰요")
    p.add_argument("--max-daily-loss", help="실계좌: 하루(한국 시간) 손실이 이만큼이면 그날 새 매수를 멈춰요")
    p.add_argument("--leverage", type=int, default=1, help=f"실계좌 선물 레버리지 (1~{LEVERAGE_CAP})")


def main(argv: list[str] | None = None) -> int:
    """python -m devlog_trade.live check|resume: 주문 없이 키를 확인하거나, 손실 상한으로 멈춘 매수를 다시 연다"""
    p = argparse.ArgumentParser(prog="devlog_trade.live", description="실계좌 키 확인과 멈춤 풀기. 주문은 내지 않아요.")
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check", help="키가 맞는지, 출금 권한이 꺼져 있는지 보고 잔고를 보여 줘요")
    c.add_argument("--market", choices=sorted(TRADERS), default="binance")
    c.add_argument("--testnet", action="store_true")
    r = sub.add_parser("resume", help="손실 상한으로 멈춘 매수를 다시 열어요")
    r.add_argument("state", help="실계좌 상태 파일")
    args = p.parse_args(argv)
    try:
        if args.command == "check":
            trader = TRADERS[args.market].from_env(args.testnet)
            trader.check_key()
            print(f"{trader.code}{' 테스트넷' if args.testnet else ''} 키 확인됨 · 출금 권한 없음 · 주문 가능 {trader.free_cash():f} {trader.currency}")
        else:
            path = Path(args.state)
            data = json.loads(path.read_text(encoding="utf-8"))
            print(f"멈춤을 풀었어요: {data.get('halted') or '멈춘 상태가 아니었어요'}")
            data["halted"] = ""
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    except (LiveOrderError, OSError, ValueError) as e:
        print(e, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
