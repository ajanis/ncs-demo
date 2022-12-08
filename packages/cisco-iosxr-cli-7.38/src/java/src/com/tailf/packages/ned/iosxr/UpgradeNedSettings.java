package com.tailf.packages.ned.iosxr;

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

    public static final String NED = "cisco-iosxr";
    public static final String NED_ID = "cisco-ios-xr-id:cisco-ios-xr";

    // Set to true and recompile NED if upgrade of ned-settings is needed
    // Note: Upgrade is needed if upgrading NED older than v6.0
    private static final boolean ENABLED = false;

    private static String[] noneGlobals = new String[] { "cisco-iosxr-proxy-settings" };
    private static HashMap<String, String> moveContainers = new HashMap<>();
    private static HashMap<String, String[][]> moveLeaves = new HashMap<>();
    private static HashMap<String, String> move = new HashMap<>();

    static {
        moveContainers.put("cisco-iosxr-proxy-settings", NED + "/proxy");
        moveContainers.put("cisco-iosxr-connection-settings", NED + "/connection");

        moveLeaves.put("cisco-iosxr-proxy-settings", new String[][] {
                new String[] {"remote-connection", "remote-connection"},
                new String[] {"remote-address", "remote-address"},
                new String[] {"remote-port", "remote-port"},
                new String[] {"proxy-prompt", "proxy-prompt"},
                new String[] {"remote-command", "remote-command"},
                new String[] {"remote-prompt", "remote-prompt"},
                new String[] {"remote-name", "remote-name"},
                new String[] {"remote-password", "remote-password"}
            });

        moveLeaves.put("cisco-iosxr-connection-settings", new String[][] {
                new String[] {"loginscript", "loginscript"},
                new String[] {"number-of-retries", "number-of-retries"},
                new String[] {"time-between-retry", "time-between-retry"}
            });

        // read
        move.put("cisco-iosxr-transaction-id-method",
                 "cisco-iosxr/read/transaction-id-method");
        move.put("cisco-iosxr-show-running-strict-mode",
                 "cisco-iosxr/read/show-running-strict-mode");
        move.put("cisco-iosxr/get-device-config-settings/method",
                 "cisco-iosxr/read/method");
        move.put("cisco-iosxr/get-device-config-settings/file",
                 "cisco-iosxr/read/file");
        move.put("cisco-iosxr/get-device-config-settings/strip-comments",
                 "cisco-iosxr/read/strip-comments");

        // write
        move.put("cisco-iosxr-commit-method",
                 "cisco-iosxr/write/commit-method");
        move.put("cisco-iosxr/apply-device-config-settings/commit-options",
                 "cisco-iosxr/write/commit-options");
        move.put("cisco-iosxr-config-method",
                 "cisco-iosxr/write/config-method");
        move.put("cisco-iosxr-number-of-lines-to-send-in-chunk",
                 "cisco-iosxr/write/number-of-lines-to-send-in-chunk");
        move.put("cisco-iosxr/apply-device-config-settings/sftp-threshold",
                 "cisco-iosxr/write/sftp-threshold");
        move.put("cisco-iosxr/apply-device-config-settings/file",
                 "cisco-iosxr/write/file");
        move.put("cisco-iosxr/apply-device-config-settings/oob-exclusive-retries",
                 "cisco-iosxr/write/oob-exclusive-retries");

        // auto
        move.put("cisco-iosxr-auto/vrf-forwarding-restore",
                 "cisco-iosxr/auto/vrf-forwarding-restore");
        move.put("cisco-iosxr-auto/CSCtk60033-patch",
                 "cisco-iosxr/auto/CSCtk60033-patch");

        move.put("cisco-iosxr/behaviour/prefer-platform-serial-number",
                 "cisco-iosxr/connection/prefer-platform-serial-number");

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

        // cisco-iosxr-config-warning
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-iosxr-config-warning");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-iosxr-config-warning[%d]/warning", offset);
                String path = root+"cisco-iosxr/write/config-warning{\""+keybuf.toString()+"\"}";
                logout("UPGRADE: Created ned-setting list: "+path);
                newMaapi.create(th, path);
            }
        } catch (Exception e) {
            // Ignore Exception
        }

        // cisco-iosxr-inject-command *
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-iosxr-inject-command");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-iosxr-inject-command[%d]/id", offset);
                String frompath = String.format("%scisco-iosxr-inject-command[%d]", root, offset);
                String topath = root+"cisco-iosxr/write/inject-command{\""+keybuf.toString()+"\"}";
                logout("UPGRADE: Created ned-setting list: "+topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/config", topath + "/config");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/command", topath + "/command");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/where", topath + "/where");
            }
        } catch (Exception e) {
            // Ignore Exception
        }

        // cisco-iosxr-auto-prompts *
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-iosxr-auto-prompts");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-iosxr-auto-prompts[%d]/id", offset);
                String frompath = root+"cisco-iosxr-auto-prompts["+offset+"]";
                String topath = root+"cisco-iosxr/live-status/auto-prompts{\""+keybuf.toString()+"\"}";
                logout("UPGRADE: Created ned-setting list: "+topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/question", topath + "/question");
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/answer", topath + "/answer");
            }
        } catch (Exception e) {
            // Ignore Exception
        }
    }


    /**
     * Upgrade leaf
     * @param
     */
    private static void upgradeLeaf(CdbSession oldcdb, Maapi newMaapi, int th,
                                    String fromPath, String toPath) {
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
        } catch (Exception e) {
            // Ignore Exception
        }
    }

}
