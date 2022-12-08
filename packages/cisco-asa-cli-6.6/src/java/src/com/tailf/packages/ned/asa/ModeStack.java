package com.tailf.packages.ned.asa;

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
    private Deque<String> mode = new LinkedList<String>();


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
            mode.addLast(last);
        } else if (spacesLine < spacesLast && mode.size() > 0) {
            // Leaving mode
            mode.removeLast();
        }

        last = line;
    }


    /**
     * Return mode stack String
     * @return
     */
    public String toString() {
        if (mode.size() == 0) {
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

}
