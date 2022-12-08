package com.tailf.packages.ned.iosxr;

import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * ModeStack
 *
 */
@SuppressWarnings("deprecation")
public class ModeStack {

    private String last;
    private Deque<String> mode = new LinkedList<>();


    /*
     **************************************************************************
     * Constructor
     **************************************************************************
     */

    /**
     * Constructor
     */
    public ModeStack() {
        this.last = "";
    }


    /**
     * Update mode stack
     * @param
     */
    public void update(String line) {

        // Count initial spaces
        int spacesLast = last.indexOf(last.trim());
        int spacesLine = line.indexOf(line.trim());

        if (spacesLine > spacesLast) {
            // Entering mode
            mode.addFirst(last);
        } else if (spacesLine < spacesLast && !mode.isEmpty()) {
            // Leaving mode
            mode.removeFirst();
        }

        last = line;
    }


    /**
     * Return mode stack String
     * @return
     */
    public String toString() {
        if (mode.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" [ ");
        Iterator it = mode.iterator();
        while (it.hasNext()) {
            String line = (String)it.next();
            sb.append(line.trim() + " / ");
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Return mode String at index. Use negative to look backwards
     * @return
     */
    public String get(int index) {
        Iterator it;

        if (index >= 0) {
            it = mode.iterator();
        } else {
            it = mode.descendingIterator();
            index = -index;
        }
        int i = 0;
        while (it.hasNext()) {
            String line = (String)it.next();
            if (i++ == index) {
                return line;
            }
        }
        return null; // error, not found
    }
}
