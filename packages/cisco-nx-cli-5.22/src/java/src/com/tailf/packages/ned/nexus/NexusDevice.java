package com.tailf.packages.ned.nexus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tailf.ned.NedException;
import com.tailf.ned.NedWorker;
import com.tailf.ned.SSHSessionException;

import com.tailf.packages.ned.nedcom.NedSettings;

import static com.tailf.packages.ned.nedcom.NedString.stringDequote;


/**
 * Implements the base class used for accessing Cisco Nexus devices.
 *
 */
abstract class NexusDevice {

    boolean waitForEcho = true;
    protected NexusNedCli ned;
    protected String lastReply;

    enum CommandType {
        EXEC_CMD,
        CONFIG_CMD,
        FIRST_CONFIG_CMD,
    }

    public NexusDevice(NexusNedCli ned) {
        this.ned = ned;
    }

    protected boolean isCliRetry(String reply) {
        String[] isRetry = {
            "wait for it to complete",
            "re-try this command at a later time",
            "please try creating later",
            "is being removed, retry later",
            "wait for the system",
            "unable to confirm radius group name with aaa daemon",
            "vrf in delete pending/holddown",
            "validation timed out",
            "delete in progress",
            "please check if command was successful",
            "irresponsive app timeout",
            "cli server not ready",
            "commit is in progress"
            // TODO: Might want more (below from IOS):
            //            "is in use",
            //            "is still in use and cannot be removed",
            //            "is currently being deconfigured"
        };

        reply = reply.replaceAll("[\n\r]", "");

        // Retry on these patterns:
        for (int n = 0; n < isRetry.length; n++) {
            if (reply.toLowerCase().matches(".*"+isRetry[n]+".*")) {
                return true;
            }
        }

        // Do not retry
        return false;
    }

    protected int cliDelay(String line) {
        String[] needDelay = {
            "no feature nv overlay", // Device is "unstable" for a while, can block if commands comes too soon
            "no feature sla", // Device doesn't "commit" this instantly, wait some...
            "feature interface-vlan", // Takes some time to enable sometimes
            "no vrf context", // Otherwise we're stuck with 'VRF in Delete Pending' afterwards
            "no member [0-9\\.]+ \\d+", // seems addr/vc-id is not released immediately (in l2vpn xconnect)
            "no member pseudowire[0-9]+" // seems pw is not released immediately (in l2vpn xconnect)
        };
        int[] delays = {
            3*1000,
            6*1000,
            3*1000,
            3*1000,
            2*1000,
            2*1000
        };
        for (int n = 0; n < needDelay.length; n++) {
            if (line.trim().toLowerCase().matches(".*"+needDelay[n]+".*")) {
                return delays[n];
            }
        }
        return -1;
    }

    private final static  List <String> errignore =
        Arrays.asList("ERROR: Cannot delete class-map name class-default",
                      "ERROR: Predefined class-maps cannot be removed",
                      "Note:  Failed",
                      "VPC peer keep-alive send has failed",
                      "abling error-stats ... was successful",
                      "Failed login statistics will be cleared"
                      );
    // The following strings is an error -> abort transaction
    private final static String[] errfail = {
        "duplicate name",
        "error",
        "exceeded",
        "failed",
        "incomplete",
        "invalid",
        "cannot set interface",
        "cannot set next-hop",
        "not allowed",
        "already exists",
        "cannot match on",
        "cannot be removed/done",
        "cannot add",
        "password is weak",
        "delete static",
        "configure tcam region and retry the command",
        "can't configure addres",
        "auth passphrase specified is not strong enough",
        "password strength check:",
        "password not strong enough",
        "overlapping network .* already configured on",
        "cannot remove primary address",
        "acl with given name exists with different type",
        "wrong password, reason",
        "reserved vlans ",
        "baud rate of console should be at least 38400 to increase logging level",
        "ingress replication can not be enabled",
        "cannot enable ingress replication",
        "cannot configure ingress",
        "'ngmvpn' not enabled",
        "please unconfigure global configurations before",
        "please specify a unique range",
        "cannot apply qos policy",
        "not consistent with sensor-group",
        "property updates not allowed on",
        "unsupported non native",
        "cannot apply non-existing",
        "is not configured",
        "feature not enabled",
        "command rejected",
        "device alias already present",
        "zone not present",
        "is configured as virtual address",
        "duplicate sequence number",
        "l2 sub-interface is not supported"
    };

    private ArrayList<Pattern> abortRegexList = new ArrayList<>();
    private ArrayList<Pattern> ignoreRegexList = new ArrayList<>();

    protected boolean isCliError(NedWorker worker, String reply) {
        for (Pattern abortPat : abortRegexList) {
            Matcher m = abortPat.matcher(reply);
            if (m.find()) {
                ned.logInfo(worker, String.format("Found match in config-abort-warning '%s' in: %s",
                                                  abortPat.toString(), reply));
                return true;
            }
        }

        for (Pattern ignorePat : ignoreRegexList) {
            Matcher m = ignorePat.matcher(reply);
            if (m.find()) {
                ned.logInfo(worker, String.format("Found match in config-ignore-warning '%s' in: %s",
                                                  ignorePat.toString(), reply));
                return false;
            }
        }

        for (String ignore : errignore) {
            if (reply.indexOf(ignore) >= 0) {
                ned.logDebug(worker, "ignoring error: " + reply);
                return false;
            }
        }

        reply = reply.replaceAll("[\n\r]", "");

        // Fail on errors
        for (int n = 0; n < errfail.length; n++) {
            if (reply.toLowerCase().matches(".*"+errfail[n]+".*")) {
                return true;
            }
        }

        // Success
        return false;
    }

    protected void updateAbortRegexList(NedWorker worker, List<Map<String,String>> entries) {
        // Append dynamic warnings
        abortRegexList.clear();
        for (Map<String,String> entry : entries) {
            String regex = entry.get("__key__");
            ned.logInfo(worker, String.format("Add config-abort-warning: '%s'", regex));
            abortRegexList.add(Pattern.compile(stringDequote(regex), Pattern.MULTILINE));
        }
    }

    protected void updateIgnoreRegexList(NedWorker worker, List<Map<String,String>> entries) {
        // Append dynamic warnings
        ignoreRegexList.clear();
        for (Map<String,String> entry : entries) {
            String regex = entry.get("__key__");
            ned.logInfo(worker, String.format("Add config-ignore-error: '%s'", regex));
            ignoreRegexList.add(Pattern.compile(stringDequote(regex), Pattern.MULTILINE));
        }
    }

    protected void sleep(NedWorker worker, long milliseconds, boolean doLog) {
        if (doLog) {
            ned.logInfo(worker, "Sleeping " + milliseconds + " milliseconds");
        }
        try {
            Thread.sleep(milliseconds);
            if (doLog) {
                ned.logInfo(worker, "Woke up from sleep");
            }
        } catch (InterruptedException e) {
            ned.logInfo(worker, "NexusDevice sleep interrupted");
            Thread.currentThread().interrupt();
        }
    }

    public abstract void
    connect(NedWorker worker, NedSettings nedSettings)
        throws IOException, SSHSessionException, NedException;

    public abstract String
    setup(NedWorker worker, NedSettings nedSettings)
      throws Exception;

    public boolean isAlive() {
        if (ned.session == null) {
            return false;
        }
        return ned.session.serverSideClosed() == false;
    }

    public abstract void
    applyConfig(NedWorker worker, int cmd, String data)
        throws NedException, IOException, SSHSessionException;

    public abstract void saveConfig(NedWorker worker)
        throws NedException, IOException, SSHSessionException;

    public void
    close() throws NedException, IOException {
        // clean up if necessary
    }

    public abstract String getConfig(NedWorker worker) throws Exception;

    public abstract String
    command(NedWorker worker, String cmd, CommandType cmdType, String[] answers) throws Exception;

    public abstract String
    command(NedWorker worker, String cmd, boolean config) throws Exception;

    public String
    command(NedWorker worker, String cmd) throws Exception {
        return this.command(worker, cmd, CommandType.EXEC_CMD, null);
    }
}
