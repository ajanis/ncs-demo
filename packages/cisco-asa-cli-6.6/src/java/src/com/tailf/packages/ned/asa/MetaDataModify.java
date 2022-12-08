package com.tailf.packages.ned.asa;
import static com.tailf.packages.ned.nedcom.NedString.getMatch;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

import com.tailf.conf.ConfPath;
import com.tailf.conf.ConfValue;

import com.tailf.maapi.Maapi;


// META SYNTAX:
// ================================
// metas[0] = ! meta-data
// metas[1] = path
// metas[2] = annotation name
// metas[3..N] = meta value(s)

//
// Supported meta annotations:
//   context-config-url
//   context-delete


/**
 * Utility class for modifying config data based on YANG model meta data provided by NCS.
 *
 * @author lbang
 * @version 20170917
 */
@SuppressWarnings("deprecation")
public class MetaDataModify {

    /*
     * Local data
     */
    private ASANedCli owner;
    private boolean autoConfigUrlFileDelete;

    /**
     * Constructor
     */
    MetaDataModify(ASANedCli owner) throws Exception {
        this.owner = owner;

        // ned-settings
        autoConfigUrlFileDelete = owner.nedSettings.getBoolean("auto/context-config-url-file-delete");
    }


    /**
     * Modify config data based on meta-data given by NCS.
     *
     * @param data - config data from applyConfig, before commit
     * @return Config data modified after parsing !meta-data tags
     */
    public String modifyData(NedWorker worker, String data, Maapi mm, int fromTh) throws NedException {
        String[] lines = data.split("\n");
        StringBuilder first = new StringBuilder();
        StringBuilder sb = new StringBuilder();
        StringBuilder last = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) {
                continue;
            }
            if (lines[i].trim().startsWith("! meta-data :: /ncs:devices/device{") == false) {
                sb.append(lines[i] + "\n");  // Normal config line -> add
                continue;
            }

            // Find command index (reason: can be multiple meta-data tags per command)
            int cmd = getCmd(lines, i + 1);
            if (cmd == -1) {
                continue;
            }
            String line = lines[cmd];
            String trimmed = lines[cmd].trim();
            String output = null;

            // Extract meta-data and meta-value(s), store in metas[] where:
            // metas[1] = meta path
            // metas[2] = meta tag name
            // metas[3] = first meta-value (each value separated by ' :: '
            String meta = lines[i].trim();
            String[] metas = meta.split(" :: ");
            String metaPath = metas[1];
            String metaTag = metas[2];


            // context-config-url
            // ==================
            // Delete context file on disk when config-url is set
            if (metaTag.equals("context-config-url")) {
                if (owner.isNetsim() || line.startsWith(" config-url") == false
                    || autoConfigUrlFileDelete == false) {
                    continue;
                }
                String filename = getMatch(line, "config-url[ ]+(\\S+)");
                String delcmd = "delete /noconfirm "+filename;
                traceInfo(worker, "meta-data "+metaTag+" :: transformed => injected '"+delcmd+"'");
                sb.append(delcmd + "\n");
                continue;
            }

            // context-delete
            // ==============
            // Delete context file on disk when context is deleted
            else if (metaTag.equals("context-delete")) {
                if (owner.isNetsim() || !line.startsWith("no context ")) {
                    continue;
                }
                String urlpath = metaPath+"/config-url";
                traceVerbose(worker, "URLPATH="+urlpath);
                try {
                    if (owner.maapiExists(worker, fromTh, urlpath)) {
                        String filename = ConfValue.getStringByValue(urlpath, owner.maapi.getElem(fromTh, urlpath));
                        String delcmd = "delete /noconfirm "+filename;
                        traceInfo(worker, "meta-data "+metaTag+" :: transformed => injected '"+delcmd+"'");
                        sb.append(lines[cmd] + "\n"); // Add 'no context ..'
                        lines[cmd] = delcmd; // Add 'delete ..'
                    } else {
                        traceInfo(worker, "meta-data "+metaTag+" WARNING missing "+urlpath);
                    }
                } catch (Exception ignore) {
                    // Ignore Exception
                }
                continue;
            }

            // prefer-high
            // ===========
            // Moved line first if value is increased, else last.
            else if (metaTag.equals("prefer-high")) {
                String newBuf = getMatch(line, metas[3]);
                if (newBuf == null) {
                    continue;
                }
                if (trimmed.startsWith("no ")) {
                    newBuf = metas[4];
                }
                int newValue = Integer.parseInt(newBuf);

                String oldBuf = owner.maapiGetLeafString(fromTh, metaPath);
                if (oldBuf == null) {
                    oldBuf = metas[4];
                }
                int oldValue = Integer.parseInt(oldBuf);

                if (newValue > oldValue) {
                    traceInfo(worker, "meta-data "+metaTag+" :: transformed => moved '"+line+"' first");
                    first.append(line+"\n");
                } else {
                    traceInfo(worker, "meta-data "+metaTag+" :: transformed => moved '"+line+"' last");
                    last.append(line+"\n");
                }
                lines[cmd] = ""; // Strip the line since moved
                continue;
            }

            // metaTag not handled by this loop -> copy it over
            else {
                sb.append(lines[i] + "\n");
            }

            //
            // Simple transformations -> log and change transformed line 'lines[cmd]'
            //
            if (output != null && !output.equals(lines[cmd])) {
                if (output.isEmpty()) {
                    traceInfo(worker, "meta-data "+metaTag+" :: transformed => stripped '"+trimmed+"'");
                } else {
                    traceInfo(worker, "meta-data "+metaTag+" :: transformed => '"+trimmed+"' to '"+output+"'");
                }
                lines[cmd] = output;
                // note: meta tag is discarded automatically here since not added
            }
        }

        return "\n" + first.toString()+ sb.toString() + last.toString() + "\n";
    }


    /**
     *
     * @param
     * @return
     */
    private int getCmd(String[] lines, int i) {
        for (int cmd = i; cmd < lines.length; cmd++) {
            String trimmed = lines[cmd].trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.startsWith("! meta-data :: /ncs:devices/device{"))
                continue;
            return cmd;
        }
        return -1;
    }


    /*
     * Write info in NED trace
     *
     * @param info - log string
     */
    private void traceInfo(NedWorker worker, String info) {
        this.owner.traceInfo(worker, info);
    }


    /*
     * Write info in NED trace if verbose output
     *
     * @param info - log string
     */
    private void traceVerbose(NedWorker worker, String info) {
        this.owner.traceVerbose(worker, info);
    }
}
