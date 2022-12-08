package com.tailf.packages.ned.asa;

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

    public static final String NED = "cisco-asa";
    public static final String NED_ID = "asa-id:cisco-asa";

    private static HashMap<String, String> move = new HashMap<>();

    static {

        // connection
        move.put("cisco-asa/connection-settings/number-of-retries",
                 "cisco-asa/connection/number-of-retries");
        move.put("cisco-asa/connection-settings/time-between-retry",
                 "cisco-asa/connection/time-between-retry");
        move.put("cisco-asa/connection-settings/prompt-timeout",
                 "cisco-asa/connection/prompt-timeout");

        // read
        move.put("cisco-asa-transaction-id-method",
                 "cisco-asa/read/transaction-id-method");
        move.put("cisco-asa/get-device-config-settings/use-startup-config",
                 "cisco-asa/read/use-startup-config");

        // write
        move.put("cisco-asa-write-memory-setting",
                 "cisco-asa/write/memory-setting");
        move.put("cisco-asa/apply-device-config-settings/config-session-mode",
                 "cisco-asa/write/config-session-mode");
        move.put("cisco-asa/apply-device-config-settings/number-of-lines-to-send-in-chunk",
                 "cisco-asa/write/number-of-lines-to-send-in-chunk");
        move.put("cisco-asa/apply-device-config-settings/compress-acl-delete",
                 "cisco-asa/write/compress-acl-delete");

        // context
        move.put("cisco-asa-context-name",
                 "cisco-asa/context/name");

    }

    public UpgradeNedSettings() {
        // Empty Constructor
    }

    /**
     * Main
     * @param
     * @return
     * @throws Exception
     */
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {

        if (!ENABLED) {
            System.out.println("UPGRADE: Ignored upgrade of cisco-asa ned-settings (Enable and recompile if needed)");
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

            // Upgrade profile ned-settings
            int no = oldcdb.getNumberOfInstances("/devices/profiles/profile");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf nameBuf = (ConfBuf)oldcdb.getElem("/devices/profiles/profile[%d]/name", offset);
                moveNedSettings(oldcdb, newMaapi, th, "/devices/profiles/profile{"+nameBuf+"}/ned-settings/");
            }

            // Upgrade device ned-settings
            no = oldcdb.getNumberOfInstances("/devices/device");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf nameBuf = (ConfBuf)oldcdb.getElem("/devices/device[%d]/name", offset);
                ConfIdentityRef id = (ConfIdentityRef)
                    oldcdb.getElem("/devices/device[%d]/device-type/cli/ned-id", offset);
                if (id != null && id.toString().equals(NED_ID)) {
                    moveNedSettings(oldcdb, newMaapi, th, "/devices/device{"+nameBuf+"}/ned-settings/");
                }
            }

            // Close
            newMaapi.detach(th);
            s1.close();
            s2.close();

        } catch (Exception e) {
            System.err.println("ERROR: UpgradeNedSettings Exception :: "+e.getMessage());
            System.exit(1);
        }
    }


    /**
     * Upgrade leaf
     * @param
     * @return
     * @throws Exception
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

        // cisco-asa-context-list
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-asa-context-list");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-asa-context-list[%d]/name", offset);
                String frompath = String.format(root+"cisco-asa-context-list[%d]", offset);
                String topath = root+"cisco-asa/context/list{\""+keybuf.toString()+"\"}";
                System.out.println("UPGRADE: Created ned-setting list: "+topath);
                newMaapi.create(th, topath);
                upgradeLeaf(oldcdb, newMaapi, th, frompath + "/hide-configuration", topath + "/hide-configuration");
            }
        } catch (Exception ignore) {
            // Ignore Exception
        }

        // cisco-asa-auto-prompts
        try {
            int no = oldcdb.getNumberOfInstances(root+"cisco-asa-auto-prompts");
            for (int i = 0; i < no; i++) {
                Integer offset = Integer.valueOf(i);
                ConfBuf keybuf = (ConfBuf)oldcdb.getElem(root+"cisco-asa-auto-prompts[%d]/id", offset);
                String frompath = String.format(root+"cisco-asa-auto-prompts[%d]", offset);
                String topath = root+"cisco-asa/live-status/auto-prompts{\""+keybuf.toString()+"\"}";
                System.out.println("UPGRADE: Created ned-setting list: "+topath);
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
     * @return
     * @throws Exception
     */
    private static void upgradeLeaf(CdbSession oldcdb, Maapi newMaapi, int th, String fromPath, String toPath) {
        try {
            if (!oldcdb.exists(fromPath)) {
                return; // Nothing to do
            }

            if (toPath.contains("hide-configuration")) {
                if (oldcdb.exists(fromPath)) {
                    System.out.println(String.format("UPGRADE: Moved ned-setting: %s -> %s = true",
                                                     fromPath, toPath));
                    newMaapi.setElem(th, "true", toPath);
                }
            } else {
                ConfValue val = oldcdb.getElem(fromPath);
                if (val != null) {
                    System.out.println(String.format("UPGRADE: Moved ned-setting: %s -> %s = %s",
                                                     fromPath, toPath, val.toString()));
                    newMaapi.setElem(th, val, toPath);
                }
            }
        } catch (Exception ignore) {
            // Ignore exception
        }
    }

}
