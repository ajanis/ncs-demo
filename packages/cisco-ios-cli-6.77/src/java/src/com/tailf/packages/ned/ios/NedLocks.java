package com.tailf.packages.ned.ios;

import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.calculateMd5Sum;

import java.util.Set;
import java.util.List;
import java.util.HashSet;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.tailf.conf.ConfObject;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

import java.util.ArrayList;


/**
 * Utility class for cisco-ios to unlock locked config
 *
 * @author lbang
 * @version 20190623
 */

@SuppressWarnings("deprecation")
public class NedLocks {

    /*
     * Local data
     */
    private IOSNedCli owner;
    private String operPath;
    private String operList;

    /**
     * Constructor
     */
    NedLocks(IOSNedCli owner) {
        this.owner = owner;
        this.operPath = owner.operRoot + "/locks";
        this.operList = this.operPath + "{%s}";
    }


    /**
     * Delete all locks
     * @param
     */
    public void reset(NedWorker worker) {
        try {
            if (owner.cdbOper.exists(operPath)) {
                traceInfo(worker, "locks: deleted all config locks");
                owner.cdbOper.delete(operPath);
            }
        } catch (Exception e) {
            // Ignore
        }
    }


    /**
     * Inject config unlocks in output data
     * @param
     * @return
     * @throws NedException
     */
    public String inject(NedWorker worker, String data, int toTh, int fromTh, StringBuilder relock)
        throws NedException {

        data = "\n" + data;
        relock.setLength(0);

        // Inject unlock and|or relock config from oper data cache
        data = injectCached(worker, data, toTh, fromTh, relock);

        //
        // Parse 'data' output
        //
        String id;
        String toptag = "";
        String[] lines = data.split("\n");
        StringBuilder sb = new StringBuilder();
        StringBuilder first = new StringBuilder();
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Modify toptag
            if (isTopExit(line)) {
                toptag = "";
            } else if (Character.isLetter(line.charAt(0))) {
                toptag = trimmed;
            }

            //
            // ip sla * - locked by ip sla schedule *
            //
            if (toptag.startsWith("ip sla ") && (id = getMatch(trimmed, "ip sla (\\d+)")) != null) {

                //
                // Temporarily remove "ip sla schedule" to unlock "ip sla"
                //
                final String root = owner.confRoot;
                try {
                    if (owner.maapi.exists(fromTh, root + "ip/sla/schedule{"+id+"}")
                        && owner.maapi.exists(toTh, root + "ip/sla/schedule{"+id+"}")) {
                        String schedule = owner.maapiGetConfig(worker, toTh, root + "ip/sla/schedule{"+id+"}", 0);
                        if (schedule != null) {
                            schedule = schedule.trim();
                            traceInfo(worker, "transformed => pre-injected 'no "+schedule+"'");
                            sb.append("no "+schedule+"\n");
                            traceInfo(worker, "transformed => post-injected '"+schedule+"' last");
                            relock.append(schedule+"\n");
                        }
                    }
                } catch (Exception e) {
                    traceInfo(worker, "NedLocks() : exception ERROR: "+ e.getMessage());
                }

                //
                // Add back dynamically removed "ip sla reaction-configuration" entry/entries
                //
                try {
                    String react = owner.maapiGetConfig(worker, toTh, root + "ip/sla/reaction-configuration", 0);
                    if (react != null) {
                        String[] entries = react.split("\n");
                        for (int e = 0; e < entries.length; e++) {
                            if (!data.contains("\nip sla reaction-configuration "+id+" ")
                                && getMatch(entries[e], "(ip sla reaction-configuration "+id+" )") != null) {
                                traceInfo(worker, "transformed => post-injected '"+entries[e]+"' last");
                                relock.append(entries[e]+"\n");
                            }
                        }
                    }
                } catch (Exception e) {
                    traceInfo(worker, "NedLocks() : Exception ERROR: "+ e.getMessage());
                }

                //
                // Replace "ip sla" (can't modify operation line once set)
                //
                try {
                    if (owner.maapi.exists(fromTh, root + "ip/sla/ip-sla-list{"+id+"}")) {
                        String sla = owner.maapiGetConfig(worker, toTh, root + "ip/sla/ip-sla-list{"+id+"}", 0);
                        if (sla != null) {
                            // Cache old ip sla config in sla0
                            StringBuilder sla0 = new StringBuilder();
                            for (;n < lines.length; n++) {
                                String slaop = slaOperation(lines[n]);
                                if (slaop == null || !slaop.trim().startsWith("no ")) {
                                    sla0.append(lines[n] + "\n"); // trim no-operation line
                                }
                                if (isTopExit(lines[n])) {
                                    break;
                                }
                            }
                            // If new entry contains operation, replace all ip sla config
                            if (slaOperation(sla0.toString()) != null) {
                                traceInfo(worker, "transformed => modified operation, replacing ip sla "+id);
                                sb.append("no ip sla "+id+"\n");
                                sb.append(sla);
                            } else {
                                sb.append(sla0);
                            }
                            continue;
                        }
                    }
                } catch (Exception e) {
                    traceInfo(worker, "NedLocks() : Exception ERROR: "+ e.getMessage());
                }
            }

            // Add line (may be empty due to stripped deleted address)
            if (!lines[n].trim().isEmpty()) {
                sb.append(lines[n]+"\n");
            }
        }

        // Done
        return "\n" + first.toString() + sb.toString() + relock.toString();
    }


    /**
     * Inject config unlocks
     * @param
     * @return
     * @throws NedException
     */
    public String injectCached(NedWorker worker, String data, int toTh, int fromTh, StringBuilder relock)
        throws NedException {
        try {
            // Read locks list from oper data in CDB
            int num = owner.cdbOper.getNumberOfInstances(this.operPath);
            if (num <= 0) {
                return data;
            }

            // Read the oper data list in one chunk
            traceInfo(worker, "locks: read "+num+" config lock(s)");
            List<ConfObject[]> list = owner.cdbOper.getObjects(4, 0, num, this.operPath);

            // Loop through all lock oper entries
            Set<String> injected = new HashSet<>(); // keep track of what we injected to avoid duplicates
            Set<String> triggered = new HashSet<>();
            ArrayList<String> deleteList = new ArrayList<>();
            for (int loop = 1; true; loop++) {
                traceDev(worker, "locks: inject loop #"+loop);
                StringBuilder first = new StringBuilder();
                for (int n = 0; n < list.size(); n++) {
                    final ConfObject[] objs = list.get(n);
                    final String id = objs[0].toString();
                    String path = objs[1].toString();

                    traceVerbose(worker, "locks: checking "+shortpath(path));

                    // Already triggered in previous loop
                    if (triggered.contains(id)) {
                        continue;
                    }

                    // Config is deleted in CDB, don't need to unlock
                    if (!owner.maapiExists(worker, toTh, path)) {
                        owner.traceInfo(worker, "locks: deleting (missing in CDB) : " + shortpath(path));
                        deleteList.add(id);
                        triggered.add(id);
                        continue;
                    }

                    // Config is newly created
                    if (!owner.maapiExists(worker, fromTh, path)) {
                        owner.traceInfo(worker, "locks: ignored, created : " + shortpath(path));
                        triggered.add(id);
                        continue;
                    }

                    // Check if lock trigger is in this commit
                    boolean doRelock = true;
                    String trigger = objs[2].toString();
                    if (trigger.contains("\n")) {
                        trigger = trigger.replace("<S>", "\\S+");
                        traceDev(worker, "   regex trigger = "+stringQuote(trigger));
                        Pattern p = Pattern.compile("("+trigger+")", Pattern.DOTALL);
                        Matcher m = p.matcher(data);
                        if (!m.find()) {
                            traceDev(worker, "locks: ignored, no trigger in commit");
                            continue;
                        }
                    } else {
                        traceDev(worker, "   trigger = "+stringQuote(trigger));
                        if (!data.contains("\n"+trigger+"\n")) {
                            traceDev(worker, "locks: ignored, no trigger in commit");
                            continue;
                        }
                        if (data.contains("\nno "+trigger+"\n")) {
                            traceVerbose(worker, "locks: ignore relock, trigger deleted");
                            doRelock = false;
                        }
                    }

                    // Found entry, unlock and lock
                    triggered.add(id);

                    if (path.contains("ip/sla/ethernet-monitor/schedule")) {
                        path = path + "/../"; // dirty patch for ip sla ethernet-monitor schedule mod
                    }
                    final String from = owner.maapiGetConfig(worker, fromTh, path, 0);
                    traceDev(worker, "   from = "+stringQuote(from));
                    final String to = owner.maapiGetConfig(worker, toTh, path, 0);
                    traceDev(worker, "   to = "+stringQuote(to));

                    // Unlock
                    final String unlock = objs[3].toString(); // already ends with \n
                    if (!data.contains("\n"+unlock)&& !injected.contains(unlock)) {
                        traceInfo(worker, "transformed => locks: pre-injected unlock "+stringQuote(unlock));
                        first.insert(0, unlock);
                        injected.add(unlock);
                    } else {
                        traceDev(worker, "locks: ignored, identical unlock already injected");
                    }

                    // Relock (only if trigger not deleted and lock did not change)
                    final String lock = unlock.replace("no ", "");
                    if (doRelock && to.equals(from) && !injected.contains(lock)) {
                        traceInfo(worker, "transformed => locks: post-injected relock "+stringQuote(lock));
                        relock.append(lock);
                        injected.add(lock);
                    } else {
                        traceDev(worker, "locks: ignored, identical relock already injected");
                    }
                }

                // Nothing injected
                if (first.length() == 0) {
                    // break out of outer for-loop
                    break;
                }

                // Update data for next loop
                data = "\n" + first.toString() + data.trim() + "\n";
            }

            // Delete entries which no longer have the config in cdb
            Iterator it = deleteList.iterator();
            while (it.hasNext()) {
                String hash = (String)it.next();
                owner.cdbOper.delete(String.format(this.operList, hash));
            }

            // Done
            return data;

        } catch (Exception e) {
            throw new NedException("locks: inject Exception ERROR :: "+e.getMessage(), e);
        }
    }


    /**
     * Check if line is top exit
     * @param
     * @return
     */
    private boolean isTopExit(String line) {
        line = line.replace("\r", "");
        if ("exit".equals(line)) {
            return true;
        }
        return "!".equals(line);
    }


    /**
     * Check if line is sla operation
     * @param
     * @return
     */
    private String slaOperation(String line) {
        if (line.contains(" icmp-echo ")){
            return line;
        }
        if (line.contains(" tcp-connect ")) {
            return line;
        }
        if (line.contains(" udp-jitter ")) {
            return line;
        }
        if (line.contains(" udp-echo ")) {
            return line;
        }
        if (line.contains(" http get ")) {
            return line;
        }
        if (line.contains(" ethernet y1731")) {
            return line;
        }
        return null;
    }


    /**
     * Wrappers to write to trace
     * @param
     */
    private void traceInfo(NedWorker worker, String info) {
        owner.traceInfo(worker, info);
    }
    private void traceVerbose(NedWorker worker, String info) {
        owner.traceVerbose(worker, info);
    }
    private void traceDev(NedWorker worker, String info) {
        owner.traceDev(worker, info);
    }

    private String shortpath(String path) {
        return owner.shortpath(path);
    }

}
