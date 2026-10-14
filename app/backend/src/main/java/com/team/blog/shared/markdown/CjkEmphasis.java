package com.team.blog.shared.markdown;

import java.util.ArrayList;
import java.util.List;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Node;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;

/**
 * 한국어·일본어·중국어 글에서 깨지는 굵게(**…**)를 살린다 (spec 097 FR-002).
 *
 * CommonMark는 닫는 **가 문장 부호 뒤에 있고 바로 뒤에 글자가 오면 닫는 표시로 보지 않는다.
 * 그래서 `**"이 일을 해도 되나?"**를`처럼 따옴표·괄호로 끝나는 굵은 글에 조사가 붙으면 별표가 글자로 남는다.
 * 파싱이 끝난 뒤 같은 부모 안에 짝 없이 글자로 남은 ** 두 개를 찾아, 그 사이를 굵게 바꾼다.
 * 영어 글의 동작을 바꾸지 않도록, 여는 **의 앞이나 닫는 **의 뒤가 CJK 글자일 때만 고친다.
 * 안쪽 끝이 공백이면(`** 굵게 **`) 의도한 별표로 보고 그대로 둔다.
 */
final class CjkEmphasis {
    private static final String MARK = "**";

    private CjkEmphasis() {
    }

    static void fix(Node doc) {
        List<Node> parents = new ArrayList<>();
        doc.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                if (text.getLiteral().contains(MARK) && !parents.contains(text.getParent())) parents.add(text.getParent());
            }

            @Override
            public void visit(Code code) {
                // 코드 안의 별표는 건드리지 않는다
            }
        });
        for (Node parent : parents) fixChildren(parent);
    }

    /** 부모 안에서 왼쪽부터 짝을 지어 바꾼다. 한 번 바꾸면 자식 목록이 달라지므로 처음부터 다시 찾는다. */
    private static void fixChildren(Node parent) {
        for (int guard = 0; guard < 100 && fixOnce(parent); guard++) {
            // 바꿀 짝이 없을 때까지 되풀이한다
        }
    }

    private static boolean fixOnce(Node parent) {
        for (Node open = parent.getFirstChild(); open != null; open = open.getNext()) {
            if (!(open instanceof Text openText)) continue;
            int from = 0;
            while (true) {
                int o = openText.getLiteral().indexOf(MARK, from);
                if (o < 0) break;
                from = o + MARK.length();
                if (tryClose(openText, o)) return true;
            }
        }
        return false;
    }

    /** openText의 at 자리 **를 여는 표시로 보고, 같은 부모 안 뒤쪽에서 닫는 **를 찾는다. */
    private static boolean tryClose(Text openText, int at) {
        String lit = openText.getLiteral();
        int innerStart = at + MARK.length();
        char before = at > 0 ? lit.charAt(at - 1) : charBefore(openText);
        // 여는 쪽 안쪽은 공백이 아니어야 한다
        if (innerStart < lit.length() && Character.isWhitespace(lit.charAt(innerStart))) return false;
        if (innerStart < lit.length() && lit.charAt(innerStart) == '*') return false;
        for (Node n = openText; n != null; n = n.getNext()) {
            if (!(n instanceof Text t)) continue;
            String s = t.getLiteral();
            int searchFrom = n == openText ? innerStart + 1 : 0;
            int c = s.indexOf(MARK, searchFrom);
            while (c >= 0) {
                char insideEnd = c > 0 ? s.charAt(c - 1) : lastCharBefore(t);
                char after = c + MARK.length() < s.length() ? s.charAt(c + MARK.length()) : charAfter(t);
                boolean innerOk = insideEnd != 0 && !Character.isWhitespace(insideEnd) && insideEnd != '*';
                boolean cjk = isCjk(before) || isCjk(after);
                if (innerOk && cjk && (n != openText || c > innerStart)) {
                    wrap(openText, at, t, c);
                    return true;
                }
                c = s.indexOf(MARK, c + 1);
            }
        }
        return false;
    }

    /** openText[at]의 **부터 closeText[c]의 **까지를 StrongEmphasis 하나로 묶는다. */
    private static void wrap(Text openText, int at, Text closeText, int c) {
        StrongEmphasis strong = new StrongEmphasis(MARK);
        if (openText == closeText) {
            String s = openText.getLiteral();
            Text inner = new Text(s.substring(at + MARK.length(), c));
            Text tail = new Text(s.substring(c + MARK.length()));
            openText.setLiteral(s.substring(0, at));
            strong.appendChild(inner);
            openText.insertAfter(strong);
            if (!tail.getLiteral().isEmpty()) strong.insertAfter(tail);
        } else {
            String o = openText.getLiteral();
            String cl = closeText.getLiteral();
            Text first = new Text(o.substring(at + MARK.length()));
            Text last = new Text(cl.substring(0, c));
            Text tail = new Text(cl.substring(c + MARK.length()));
            openText.setLiteral(o.substring(0, at));
            if (!first.getLiteral().isEmpty()) strong.appendChild(first);
            Node n = openText.getNext();
            while (n != null && n != closeText) {
                Node next = n.getNext();
                strong.appendChild(n); // appendChild가 원래 자리에서 떼어 낸다
                n = next;
            }
            if (!last.getLiteral().isEmpty()) strong.appendChild(last);
            openText.insertAfter(strong);
            if (!tail.getLiteral().isEmpty()) closeText.insertAfter(tail);
            closeText.unlink();
        }
        if (openText.getLiteral().isEmpty()) openText.unlink();
    }

    private static char charBefore(Node n) {
        return n.getPrevious() instanceof Text t && !t.getLiteral().isEmpty() ? t.getLiteral().charAt(t.getLiteral().length() - 1) : ' ';
    }

    private static char lastCharBefore(Node n) {
        if (n.getPrevious() == null) return 0;
        return n.getPrevious() instanceof Text t && !t.getLiteral().isEmpty() ? t.getLiteral().charAt(t.getLiteral().length() - 1) : 'x';
    }

    private static char charAfter(Node n) {
        return n.getNext() instanceof Text t && !t.getLiteral().isEmpty() ? t.getLiteral().charAt(0) : ' ';
    }

    static boolean isCjk(char ch) {
        Character.UnicodeScript script = Character.UnicodeScript.of(ch);
        return script == Character.UnicodeScript.HANGUL || script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA;
    }
}
