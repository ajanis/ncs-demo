package com.tailf.packages.ned.ios;

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
            mode.addLast(last);
        } else if (spacesLine < spacesLast && !mode.isEmpty()) {
            // Leaving mode
            mode.removeLast();
        }

        last = line;
    }


    /**
     * Return mode stack in single pretty line
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
     * Return mode stack as CLI commands to enter mode
     * @return
     */
    public String toEnter() {
        if (mode.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator it = mode.iterator();
        while (it.hasNext()) {
            sb.append((String)it.next()+"\n");
        }
        return sb.toString();
    }


    /**
     * Return mode stack as CLI commands to exit mode
     * @return
     */
    public String toExit() {
        if (mode.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        StringBuilder exit = new StringBuilder("exit\n");
        Iterator it = mode.iterator();
        while (it.hasNext()) {
            it.next();
            sb.insert(0, exit.toString());
            exit.insert(0, " ");
        }
        return sb.toString();
    }
}
