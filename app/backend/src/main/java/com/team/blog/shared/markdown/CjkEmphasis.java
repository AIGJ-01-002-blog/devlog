package com.team.blog.shared.markdown;

import java.util.LinkedHashSet;
import java.util.Set;

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
        // Node는 equals를 재정의하지 않으므로 같은 객체로 거른다. 순서는 문서 순서를 지킨다
        Set<Node> parents = new LinkedHashSet<>();
        doc.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                if (text.getLiteral().contains(MARK)) parents.add(text.getParent());
            }

            @Override
            public void visit(Code code) {
                // 코드 안의 별표는 건드리지 않는다
            }
        });
        for (Node parent : parents) fixChildren(parent);
    }

    /**
     * 부모 안에서 왼쪽부터 짝을 지어 바꾼다. 앞쪽 여는 표시는 이미 짝이 없다고 확인했으니
     * 바꾼 뒤에는 새 굵게 노드 다음부터 이어서 찾는다 (처음부터 다시 훑지 않는다).
     */
    private static void fixChildren(Node parent) {
        Node start = parent.getFirstChild();
        while (start != null) {
            // 렌더 시간 제한(ContentRenderer)이 스레드를 끊으면 바로 멈춘다
            if (Thread.currentThread().isInterrupted()) throw new ContentTooComplexException();
            start = fixFrom(start);
        }
    }

    /** start부터 첫 짝을 찾아 바꾸고, 이어서 찾을 노드를 돌려준다. 더 바꿀 짝이 없으면 null. */
    private static Node fixFrom(Node start) {
        for (Node open = start; open != null; open = open.getNext()) {
            if (!(open instanceof Text openText)) continue;
            int from = 0;
            while (true) {
                int o = openText.getLiteral().indexOf(MARK, from);
                if (o < 0) break;
                from = o + MARK.length();
                StrongEmphasis strong = tryClose(openText, o);
                if (strong != null) return strong.getNext();
            }
        }
        return null;
    }

    /** openText의 at 자리 **를 여는 표시로 보고, 같은 부모 안 뒤쪽에서 닫는 **를 찾는다. */
    private static StrongEmphasis tryClose(Text openText, int at) {
        String lit = openText.getLiteral();
        int innerStart = at + MARK.length();
        char before = at > 0 ? lit.charAt(at - 1) : charBefore(openText);
        // 여는 쪽 안쪽은 공백이 아니어야 한다
        if (innerStart < lit.length() && Character.isWhitespace(lit.charAt(innerStart))) return null;
        if (innerStart < lit.length() && lit.charAt(innerStart) == '*') return null;
        for (Node n = openText; n != null; n = n.getNext()) {
            if (!(n instanceof Text t)) continue;
            boolean same = n == openText;
            int c = findClose(t, same ? innerStart + 1 : 0, same ? innerStart : -1, before);
            if (c >= 0) return wrap(openText, at, t, c);
        }
        return null;
    }

    /** t 안 searchFrom부터 닫는 표시가 될 수 있는 **의 자리를 찾는다. minExclusive보다 뒤여야 한다. 없으면 -1. */
    private static int findClose(Text t, int searchFrom, int minExclusive, char before) {
        String s = t.getLiteral();
        int c = s.indexOf(MARK, searchFrom);
        while (c >= 0) {
            char insideEnd = c > 0 ? s.charAt(c - 1) : lastCharBefore(t);
            char after = c + MARK.length() < s.length() ? s.charAt(c + MARK.length()) : charAfter(t);
            boolean innerOk = insideEnd != 0 && !Character.isWhitespace(insideEnd) && insideEnd != '*';
            boolean cjk = isCjk(before) || isCjk(after);
            if (innerOk && cjk && c > minExclusive) return c;
            c = s.indexOf(MARK, c + 1);
        }
        return -1;
    }

    /** openText[at]의 **부터 closeText[c]의 **까지를 StrongEmphasis 하나로 묶는다. */
    private static StrongEmphasis wrap(Text openText, int at, Text closeText, int c) {
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
        return strong;
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
