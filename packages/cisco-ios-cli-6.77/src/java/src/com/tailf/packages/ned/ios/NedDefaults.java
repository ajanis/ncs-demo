/**
 * Utility class for injecting hidden default values if explicitly set in NSO.
 * Uses the CLI extension ios:ned-defaults "default-value".
 *
 * @author lbang
 * @version 20180510
 */

package com.tailf.packages.ned.ios;

import static com.tailf.packages.ned.nedcom.NedString.getMatch;
import static com.tailf.packages.ned.nedcom.NedString.stringQuote;
import static com.tailf.packages.ned.nedcom.NedString.calculateMd5Sum;

import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

import com.tailf.conf.ConfBuf;
import com.tailf.conf.ConfPath;


//
// NedDefaults
//
@SuppressWarnings("deprecation")
public class NedDefaults {

    private static final String[][] defaultMaps = {
        {
            "wrr-queue/cos-map{1 1} :: wrr-queue cos-map 1 1 0 1",
            "wrr-queue/cos-map{1 2} :: wrr-queue cos-map 1 2 2 3"
        },
        {
            "wrr-queue/cos-map{1 1} :: wrr-queue cos-map 1 1 0",
            "wrr-queue/cos-map{1 2} :: wrr-queue cos-map 1 2 1",
            "wrr-queue/cos-map{3 1} :: wrr-queue cos-map 3 1 6"
        }
    };

    // Constructor data:
    private IOSNedCli owner;

    // Local data:
    private String operPath;
    private String operList;
    private boolean showQueueing = true;

    /*
     * Constructor
     */
    NedDefaults(IOSNedCli owner) throws NedException {
        this.owner = owner;
        this.operPath = owner.operRoot + "/defaults";
        this.operList = this.operPath + "{%s}";
    }


    /**
     * Cache all default entries
     * @param
     * @throws NedException
     */
    public void cache(NedWorker worker, List<String>actions) throws NedException {
        for (String action : actions) {
            if (action.startsWith("cache-default :: ")) {
                cacheLine(worker, action);
            }
        }
    }


    /**
     * Cache a default value entry
     * @param
     * @throws NedException
     */
    private void cacheLine(NedWorker worker, String action) throws NedException {

        // tokens[0] = "cache-default"
        // tokens[1] = <path>
        // tokens[2] = "default-xxx"
        // tokens[3] = <command>
        final String[] tokens = action.split(" :: ");
        String id = null;
        try {
            id = calculateMd5Sum(tokens[1]);
        } catch (Exception e) {
            throw new NedException("Internal ERROR: failed to create DEFAULTS id for "+tokens[1]);
        }

        // Delete or create default entry for this path
        final String trimmed = tokens[3].trim();
        if (trimmed.startsWith("no ")) {
            operDelete(worker, action, id);
        } else {
            operCreate(worker, action, id);
        }
    }


    /**
     * Create default entry in oper data defaults list
     * @param
     */
    private void operCreate(NedWorker worker, String action, String id) {
        try {
            ConfPath cp = new ConfPath(String.format(this.operList, id));
            if (!owner.cdbOper.exists(cp)) {
                owner.traceInfo(worker, "DEFAULTS - Creating : " + action);
                owner.cdbOper.create(cp);
            }
            owner.cdbOper.setElem(new ConfBuf(action), cp.append("/action"));
        } catch (Exception e) {
            owner.logError(worker, "DEFAULTS - ERROR : failed to create "+id, e);
        }
    }


    /**
     * Delete default entry in oper data defaults list
     * @param
     */
    private void operDelete(NedWorker worker, String action, String id) {
        try {
            ConfPath cp = new ConfPath(String.format(this.operList, id));
            if (owner.cdbOper.exists(cp)) {
                if (action != null) {
                    owner.traceInfo(worker, "DEFAULTS - Deleting : " + action);
                }
                owner.cdbOper.delete(cp);
            }
        } catch (Exception e) {
            owner.logError(worker, "DEFAULTS - ERROR : failed to delete "+id, e);
        }
    }


    /**
     * Inject default values
     * @param
     * @return
     */
    public String inject(NedWorker worker, String dump, int th) throws NedException {
        StringBuilder sb = new StringBuilder();
        ArrayList<String> deleteList = new ArrayList<>();

        owner.traceVerbose(worker, "injecting default values...");

        // Read list using Maapi.getObjects
        ArrayList<String[]> list = owner.maapiGetObjects(worker, th, this.operPath, 2);
        for (String[] obj : list) {
            final String id = obj[0];
            final String action = obj[1];

            // Verify that new 'cisco-ios-oper defaults' list API
            if (obj[0] == null || obj[1] == null || !action.contains("cache-default :: ")) {
                owner.traceInfo(worker, "DEFAULTS - Deleting (old CDB oper syntax) : " + id);
                deleteList.add(id);
                continue;
            }

            // Config is deleted in CDB, don't need to inject default
            final String[] tokens = action.split(" :: ");
            final String path = tokens[1];
            if (!owner.maapiExists(worker, th, path)) {
                owner.traceInfo(worker, "DEFAULTS - Deleting (missing in CDB) : " + path);
                deleteList.add(id);
                continue;
            }

            // Verify that the interfaces still exists on device
            String ifname = getMatch(path, "interface/(\\S+?[{]\\S+?[}])");
            ifname = "interface " + ifname.replace("{", "").replace("}", "");
            if (!dump.contains("\n"+ifname)) {
                owner.traceInfo(worker, "DEFAULTS - Deleting (missing interface) : " + path);
                deleteList.add(id);
                continue;
            }

            // Re-inject default values
            String inject = null;

            //
            // interface * / priority-queue cos-map *
            //
            if ("if-priority-queue-cos-map".equals(tokens[2])) {
                inject = " priority-queue cos-map 1 5\n";
            }

            //
            // interface * / wrr-queue cos-map *
            //
            else if ("if-wrr-queue-cos-map".equals(tokens[2])) {
                int map = getWrrQueueCosMapDefaultMap(worker, ifname);
                for (int n = 0; n < defaultMaps[map].length; n++) {
                    String[] tok = defaultMaps[map][n].split(" :: ");
                    if (path.contains(tok[0])) {
                        inject = " " + tok[1] + "\n";
                        break;
                    }
                }
            }

            if (inject != null) {
                owner.traceInfo(worker, "transformed <= injected DEFAULTS: "
                                +stringQuote(inject)+" in "+stringQuote(ifname));
                sb.append(ifname+"\n"+inject+"exit\n");
            }
        }

        // Delete unfound/bad entries
        Iterator it = deleteList.iterator();
        while (it.hasNext()) {
            String id = (String)it.next();
            operDelete(worker, null, id);
        }

        owner.traceVerbose(worker, "injecting default values done");

        return sb.toString() + dump;
    }


    /**
     * Look up which queue map defaults are used on interface
     * @param
     * @return
     */
    private int getWrrQueueCosMapDefaultMap(NedWorker worker, String ifname) {
        int map = 0;

        // Show queuing to determine what type of default map
        try {
            // WS-C6504-E show queueing syntax
            String res = "Invalid input detected at";
            if (this.showQueueing) {
                String cmd = "show queueing "+ifname+" | i WRR";
                owner.traceInfo(worker, "extracting config - "+cmd);
                res = owner.print_line_exec(worker, cmd);
            }

            // 7604 show queueing syntax
            if (res.contains("Invalid input detected at")) {
                this.showQueueing = false;
                String cmd = "show mls qos queuing "+ifname+" | i WRR";
                owner.traceInfo(worker, "extracting config - "+cmd);
                res = owner.print_line_exec(worker, cmd);
            }
            if (res.contains("Invalid input detected at")) {
                this.showQueueing = true;
                owner.traceInfo(worker, "DEFAULTS - cache() ERROR :: failed to show queuing for "+ifname);
            } else if (res.contains("[queue 3]")) {
                map = 1;
            }
        } catch (Exception e) {
            owner.logError(worker, "DEFAULTS - ERROR : show queuing Exception", e);
        }

        owner.traceInfo(worker, "DEFAULTS - "+stringQuote(ifname)+" map = "+map);
        return map;
    }

}
