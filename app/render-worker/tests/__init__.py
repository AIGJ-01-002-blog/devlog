import os

# 테스트는 기본으로 Pillow 무대로 그린다(node·크롬 없이도 돌게). Remotion 경로는 RemotionTest가 따로 확인한다
os.environ.setdefault("DEVLOG_REMOTION", "0")
