package com.tailf.packages.ned.ios;

import java.net.Socket;
import java.net.InetAddress;

import java.util.HashMap;
import java.util.Map;

import com.tailf.cdb.Cdb;
import com.tailf.cdb.CdbDBType;
import com.tailf.cdb.CdbSession;
import com.tailf.conf.Conf;
import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfIdentityRef;
import com.tailf.conf.ConfValue;
import com.tailf.maapi.Maapi;

public class UpgradeNedSettings {

    // Set to true and recompile NED if upgrade of ned-settings is needed
    // Note: Upgrade is needed if upgrading NED older than v6.0
    private static final boolean ENABLED = false;

    private static final String NED = "cisco-ios";
    private static final String NED_ID = "ios-id:cisco-ios";
    private static final String REGEXP = "/regexp";


    private static String[] noneGlobals = new String[] { "cisco-ios-proxy-settings" };
    private static HashMap<String, String> moveContainers = new HashMap<>();
    private static HashMap<String, String[][]> moveLeaves = new HashMap<>();
    private static HashMap<String, String> move = new HashMap<>();

    static {
        moveContainers.put("cisco-ios-proxy-settings", NED + "/proxy");
        moveContainers.put("cisco-ios/behaviour", NED + "/write");

        moveLeaves.put("cisco-ios-proxy-settings", new String[][] {
                new String[] {"remote-connection", "remote-connection"},
                new String[] {"remote-address", "remote-address"},
                new String[] {"remote-port", "remote-port"},
                new String[] {"remote-command", "remote-command"},
                new String[] {"remote-prompt", "remote-prompt"},
                new String[] {"remote-name", "remote-name"},
                new String[] {"remote-password", "remote-password"},
                new String[] {"remote-secondary-password", "remote-secondary-password"},
                new String[] {"proxy-prompt", "proxy-prompt"},
                new String[] {"proxy-prompt2", "proxy-prompt2"},
            });

        moveLeaves.put("cisco-ios/behaviour", new String[][] {
                new String[] {"config-output-max-retries", "config-output-max-retries"},
                new String[] {"number-of-lines-to-send-in-chunk", "number-of-lines-to-send-in-chunk"}
            });

        // connection
        move.put("cisco-ios/connection-settings/number-of-retries",
                 "cisco-ios/connection/number-of-retries");
        move.put("cisco-ios/connection-settings/time-between-retry",
                 "cisco-ios/connection/time-between-retry");
        move.put("cisco-ios/connection-settings/prompt-timeout",
                 "cisco-ios/connection/prompt-timeout");
        move.put("cisco-ios/connection-settings/send-login-newline",
                 "cisco-ios/connection/send-login-newline");

        // read
        move.put("cisco-ios-transaction-id-method",
                 "cisco-ios/read/transaction-id-method");
        move.put("cisco-ios-show-running-method",
                 "cisco-ios/read/show-running-method");

        // write
        move.put("cisco-ios-connection-settings/device-output-delay",
                 "cisco-ios/write/device-output-delay");
        move.put("cisco-ios/connection-settings/device-output-delay",
                 "cisco-ios/write/device-output-delay");
        move.put("cisco-ios/connection/device-output-delay",
                 "cisco-ios/write/device-output-delay");
        move.put("cisco-ios-write-memory-method",
                 "cisco-ios/write/memory-method");
        move.put("cisco-ios-write-memory-setting",
                 "cisco-ios/write/memory-setting");

        // auto
        move.put("cisco-ios-auto/vrf-forwarding-restore",
                 "cisco-ios/auto/vrf-forwarding-restore");
        move.put("cisco-ios-auto/ip-vrf-rd-restore",
                 "cisco-ios/auto/ip-vrf-rd-restore");
        move.put("cisco-ios-auto/ip-community-list-repopulate",
                 "cisco-ios/auto/ip-community-list-repopulate");
        move.put("cisco-ios-auto/interface-switchport-status",
                 "cisco-ios/auto/interface-switchport-status");
        move.put("cisco-ios-auto/if-switchport-sp-patch",
                 "cisco-ios/auto/if-switchport-sp-patch");
        move.put("cisco-ios-auto/delete-when-empty-patch",
                 "cisco-ios/auto/delete-when-empty-patch");
        move.put("cisco-ios-auto/use-ip-mroute-cache-distributed",
                 "cisco-ios/auto/use-ip-mroute-cache-distributed");

        // api
        move.put("cisco-ios-police-format",
                 "cisco-ios/api/police-format");
        move.put("cisco-ios-api/new-ip-access-list",
                 "cisco-ios/api/new-ip-access-list");
        move.put("cisco-ios-api/access-list-resequence",
                 "cisco-ios/api/access-list-resequence");

    }

    public UpgradeNedSettings() {
        // Empty Constructor
    }

    /**
     * Main
     * @param
     * @throws Exception
     */
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {

        if (!ENABLED) {
            logout("UPGRADE: Ignored upgrade of cisco-ios ned-settings (Enable and recompile if needed)");
            return;
        }

        try {
            String localhost = InetAddress.getLoopbackAddress().getHostAddress();
            String host = System.getProperty("host", localhost);

            String port = System.getProperty("port");
            int iport = Conf.NCS_PORT;
            if (port != null) {
                iport = Integer.parseInt(port);
            }

            Socket s1 = new Socket(host, iport);
            Cdb cdb = new Cdb("cdb-upgrade-sock", s1);
            cdb.setUseForCdbUpgrade();
            CdbSession oldcdb = cdb.startSession(CdbDBType.CDB_RUNNING);

            Socket s2 = new Socket(host, iport);
            Maapi newMaapi = new Maapi(s2);
            int th = newMaapi.attachInit();

            // Upgrade global ned-settings
            moveNedSettings(oldcdb, newMaapi, th, "/devices/global-settings/ned-settings/");
            for (Map.Entry<String, String> entry : moveContainers.entrySet()) {
                String fromPath = entry.getKey();
                String toPath = entry.getValue();
                if (!isGlobal(fromPath)) {
                    continue;
                }
                for (String[] name : moveLeaves.get(fromPath)) {
                    if (!isGlobal(name[0])) {
                        continue;
                    }
                    upgradeLeaf(oldcdb, newMaapi, th,
                                String.format("/devices/global-settings/ned-settings/%s/%s",
                                              fromPath, name[0]),
                                String.format("/devices/global-settings/ned-settings/%s/%s",
                                              toPath, name[1]));
                }
            }

            // Upgrade profile ned-settings
            int no = oldcdb.getNumberOfInstances("/devices/profiles/profile");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf nameBuf = (ConfBuf)oldcdb.getElem("/devices/profiles/profile[%d]/name", offset);
                moveNedSettings(oldcdb, newMaapi, th, "/devices/profiles/profile{"+nameBuf+"}/ned-settings/");
                for (Map.Entry<String, String> entry : moveContainers.entrySet()) {
                    String fromPath = entry.getKey();
                    String toPath = entry.getValue();
                    if (!isGlobal(fromPath)) {
                        continue;
                    }
                    for (String[] name : moveLeaves.get(fromPath)) {
                        upgradeLeaf(oldcdb, newMaapi, th,
                                    String.format("/devices/profiles/profile[%d]/ned-settings/%s/%s",
                                                  offset, fromPath, name[0]),
                                    String.format("/devices/profiles/profile{%s}/ned-settings/%s/%s",
                                                  nameBuf.toString(), toPath, name[1]));
                    }
                }
            }

            // Upgrade per-device ned-settings
            no = oldcdb.getNumberOfInstances("/devices/device");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf nameBuf = (ConfBuf)oldcdb.getElem("/devices/device[%d]/name", offset);
                ConfIdentityRef id = (ConfIdentityRef)
                    oldcdb.getElem("/devices/device[%d]/device-type/cli/ned-id", offset);
                if (id != null && id.toString().equals(NED_ID)) {
                    moveNedSettings(oldcdb, newMaapi, th, "/devices/device{"+nameBuf+"}/ned-settings/");
                    for (Map.Entry<String, String> entry : moveContainers.entrySet()) {
                        String fromPath = entry.getKey();
                        String toPath = entry.getValue();
                        for (String[] name : moveLeaves.get(fromPath)) {
                            upgradeLeaf(oldcdb, newMaapi, th,
                                        String.format("/devices/device[%d]/ned-settings/%s/%s",
                                                      offset, fromPath, name[0]),
                                        String.format("/devices/device{%s}/ned-settings/%s/%s",
                                                      nameBuf.toString(), toPath, name[1]));
                        }
                    }
                }
            }

            // Close
            newMaapi.detach(th);
            s1.close();
            s2.close();
        } catch (Exception e) {
            logerr("ERROR: UpgradeNedSettings Exception :: "+e.getMessage());
            System.exit(1);
        }
    }


    /**
     *
     * @param
     * @return
     */
    private static void logout(String info) {
        System.out.println(info);
    }
    private static void logerr(String info) {
        System.err.println(info);
    }


    /**
     *
     * @param
     * @return
     */
    private static boolean isGlobal(String val) {
        for (String g : noneGlobals) {
            if (val.contains(g)) {
                return false;
            }
        }
        return true;
    }


    /**
     * Upgrade leaf
     * @param
     */
    private static void moveNedSettings(CdbSession oldcdb, Maapi newMaapi, int th, String root) {
        // Leaves
        for (Map.Entry<String, String> entry : move.entrySet()) {
            String fromPath = root + entry.getKey();
            String toPath = root + entry.getValue();
            upgradeLeaf(oldcdb, newMaapi, th, fromPath, toPath);
        }

        /*
         * lists:
         */

        // cisco-ios-replace-config
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-replace-config");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-replace-config[%d]/id", offset);
                String frompath = root+"cisco-ios-replace-config["+offset+"]";
                String topath = root+"cisco-ios/read/replace-config{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + REGEXP, topath + REGEXP);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/replacement", topath + "/replacement");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/when", topath + "/when");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-inject-config
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-inject-config");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-inject-config[%d]/id", offset);
                String frompath = root+"cisco-ios-inject-config["+offset+"]";
                String topath = root+"cisco-ios/read/inject-config{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + REGEXP, topath + REGEXP);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/config", topath + "/config");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/where", topath + "/where");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-inject-interface-config
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-inject-interface-config");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-inject-interface-config[%d]/id", offset);
                String frompath = root+"cisco-ios-inject-interface-config["+offset+"]";
                String topath = root+"cisco-ios/read/inject-interface-config{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/interface", topath + "/interface");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/config", topath + "/config");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-config-warning
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-config-warning");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-config-warning[%d]/warning", offset);
                String topath = root+"cisco-ios/write/config-warning{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-inject-command
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-inject-command");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-inject-command[%d]/id", offset);
                String frompath = root+"cisco-ios-inject-command["+offset+"]";
                String topath = root+"cisco-ios/write/inject-command{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/config-line", topath + "/config-line");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/command", topath + "/command");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/where", topath + "/where");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-replace-commit
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-replace-commit");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-replace-commit[%d]/id", offset);
                String frompath = root+"cisco-ios-replace-commit["+offset+"]";
                String topath = root+"cisco-ios/write/replace-commit{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + REGEXP, topath + REGEXP);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/replacement", topath + "/replacement");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-inject-answer
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-inject-answer");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-inject-answer[%d]/id", offset);
                String frompath = root+"cisco-ios-inject-answer["+offset+"]";
                String topath = root+"cisco-ios/write/inject-answer{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/question", topath + "/question");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/answer", topath + "/answer");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/ml-question", topath + "/ml-question");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-ios-auto-prompts
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-ios-auto-prompts");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-ios-auto-prompts[%d]/id", offset);
                String frompath = root+"cisco-ios-auto-prompts["+offset+"]";
                String topath = root+"cisco-ios/live-status/auto-prompts{\""+keybuf.toString()+"\"}";
                logUpgradeList(topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/question", topath + "/question");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/answer", topath + "/answer");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }
    }


    /**
     * Upgrade leaf
     * @param
     */
    private static void upgradeLeaf(CdbSession oldcdb, Maapi newMaapi, int th, String fromPath, String toPath) {
        try {
            if (!oldcdb.exists(fromPath)) {
                // Nothing to do
                return;
            }
            ConfValue val = oldcdb.getElem(fromPath);
            if (val != null) {
                logout(String.format("UPGRADE: Moved ned-setting: %s -> %s = %s",
                                                 fromPath, toPath, val.toString()));
                if (toPath.contains("proxy/remote-password")) {
                    newMaapi.setElem(th, val.toString(), toPath);
                } else {
                    newMaapi.setElem(th, val, toPath);
                }
            }
        } catch (Exception ignore) {
            // Ignore exception
        }
    }


    private static void logUpgradeList(String topath) {
        logout("UPGRADE: Created ned-setting list: "+topath);
    }

}
