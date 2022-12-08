
package com.tailf.packages.ned.asa;

import java.util.Map;
import java.util.List;
import java.util.Date;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedList;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedException;

/*
 * ConfigArchive
 */
@SuppressWarnings("deprecation")
class ConfigArchive {

    private static final String COPYCMD = "copy /noconfirm running-config ";

    private ASANedCli owner;
    private LinkedList<ArchiveEntry> archiveList = new LinkedList<>();

    /*
     * Constructor
     */
    ConfigArchive(ASANedCli owner) {
        this.owner = owner;
    }


    /*
     **************************************************************************
     * Public methods
     **************************************************************************
     */

    /**
     * Read config-archive ned-settings and verifying them
     * @param
     * @throws Exception
     */
    public void init(NedWorker worker) throws NedException {
        List<Map<String,String>> entries;

        entries = owner.nedSettings.getListEntries("write/config-archive");
        for (Map<String,String> entry : entries) {
            String id = entry.get("__key__"); // "id"
            boolean disabled = "true".equals(entry.get("disabled"));
            String type = entry.get("type");
            String directory = entry.get("directory");
            String filename = entry.get("filename");
            if (directory == null || filename == null) {
                throw new NedException("write/config-archive ned-setting missing directory or filename");
            }
            String remoteUser = null;
            String remotePassword = null;
            boolean repeatOnStandby = false;
            int maxFiles = 10;
            if ("remote".equals(type)) {
                remoteUser = entry.get("remote-user");
                remotePassword = entry.get("remote-password");
            } else {
                repeatOnStandby = "true".equals(entry.get("repeat-on-standby"));
                try {
                    maxFiles = Integer.parseInt(entry.get("max-files"));
                } catch (Exception e) {
                    // Ignore
                }
            }
            owner.traceInfo(worker, "Adding archive "+id);
            ArchiveEntry archive = new ArchiveEntry(id, disabled, type, directory,
                                                    filename, remoteUser, remotePassword,
                                                    repeatOnStandby, maxFiles);
            archiveList.add(archive);
        }
    }


    /**
     * Archive config
     * @param
     * @throws Exception
     */
    public void archive(NedWorker worker) throws NedException {
        if (archiveList.size() == 0) {
            return;
        }
        final long start = System.currentTimeMillis();
        owner.resetTimeout(worker, owner.writeTimeout, 0);

        owner.logInfo(worker, "BEGIN archiving");
        try {
            for (int n = 0; n < archiveList.size(); n++) {
                ArchiveEntry entry = archiveList.get(n);
                entry.archive(worker);
            }
        } finally {
            owner.setReadTimeout(worker);
        }
        owner.logInfo(worker, "DONE archiving "+owner.tickToString(start));
    }


    /*
     **************************************************************************
     * Private class
     **************************************************************************
     */

    private class ArchiveEntry {
        String id;
        boolean disabled;
        String type;
        String directory;
        String filename;
        String remoteUser;
        String remotePassword;
        boolean repeatOnStandby;
        int maxFiles;

        // ArchiveEntry.Constructor
        ArchiveEntry(String id, boolean disabled, String type, String directory, String filename,
                     String remoteUser, String remotePassword, boolean repeatOnStandby, int maxFiles) {
            this.id = id;
            this.disabled = disabled;
            this.type = type;
            this.directory = directory;
            this.filename = filename.replace("%h", owner.device_id);
            this.remoteUser = remoteUser;
            this.remotePassword = remotePassword;
            this.repeatOnStandby = repeatOnStandby;
            this.maxFiles = maxFiles;
        }


        /**
         * Archive config
         * @param
         */
        public void archive(NedWorker worker) {
            if (disabled) {
                return;
            }

            // Create file name
            String destination = this.filename;
            if (destination.contains("%d") || destination.contains("%t")) {
                Date date = new Date();
                if (destination.contains("%d")) {
                    DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
                    destination = destination.replace("%d", dateFormat.format(date));
                }
                if (destination.contains("%t")) {
                    DateFormat dateFormat = new SimpleDateFormat("HH.mm.ss");
                    destination = destination.replace("%t", dateFormat.format(date));
                }
            }
            if (destination.contains("%i")) {
                destination = destination.replace("%i", Integer.toString(owner.lastTransactionId));
            }
            destination = this.directory + destination;

            // REMOTE
            if ("remote".equals(type)) {
                owner.traceInfo(worker, "Archiving remotely ["+id+"] "+destination);
                archiveRemote(worker, destination);
            }

            // LOCAL
            else {
                owner.traceInfo(worker, "Archiving locally ["+id+"] "+destination);
                archiveLocal(worker, destination, false);
                if (this.repeatOnStandby) {
                    archiveLocal(worker, destination, true);
                }
            }
        }


        /**
         * Save remote
         * @param
         */
        private void archiveRemote(NedWorker worker, String destination) {
            String logdest = destination;

            // Add optional remoteUser & remotePassword
            if (this.remoteUser != null && !this.remoteUser.isEmpty()) {
                String user = this.remoteUser;
                if (this.remotePassword != null && !this.remotePassword.isEmpty()) {
                    String pw = this.remotePassword;
                    try {
                        pw = owner.mCrypto.decrypt(this.remotePassword);
                    } catch (Exception e) {
                        owner.logError(worker, "Archive password '"+pw+"' decrypt ERROR", e);
                    }
                    user += (":" + pw);
                    logdest = destination.replaceFirst("://", "://"+this.remoteUser+":HIDDEN@");
                } else {
                    logdest = destination.replaceFirst("://", "://"+this.remoteUser+"@");
                }
                destination = destination.replaceFirst("://", "://"+user+"@");
            }

            // Copy file to remote destination [do NOT trace password unless log-verbose]
            String cmd = COPYCMD + destination;
            try {
                if (!owner.logVerbose) {
                    owner.traceInfo(worker, "SENDING_ARCHIVE: " + COPYCMD + logdest + "\n");
                    owner.session.setTracer(null);
                }
                String res = owner.print_line_exec(worker, cmd, owner.writeTimeout);
                if (!owner.logVerbose) {
                    owner.traceInfo(worker, res);
                    owner.session.setTracer(worker);
                }
                if (isDeviceError(res)) {
                    throw new Exception(res);
                }
            } catch (Exception e) {
                if (!owner.logVerbose) {
                    owner.session.setTracer(worker);
                }
                owner.logError(worker, "Arching remotely failed: "+cmd+" :: ", e);
            }
        }


        /**
         * Save running-config locally or on standby
         * @param
         */
        private void archiveLocal(NedWorker worker, String destination, boolean onStandby) {
            String cmdPfx = onStandby ? "failover exec mate " : "";
            String where = onStandby ? "failover archive" : "archive";

            // Save running-config
            String cmd = cmdPfx + COPYCMD + destination;
            try {
                String res = owner.print_line_exec(worker, cmd);
                if (isDeviceError(res)) {
                    throw new Exception(res);
                }
            } catch (Exception e) {
                owner.logError(worker, "Saving in "+where+" failed: "+cmd+"\n", null);
                return;
            }

            if (this.maxFiles <= 0) {
                return; // No maximum, return early
            }

            //
            // Maintain a maximum of 'cisco-asa write config-archive * max-files' archive files
            //

            // List the files
            int numFiles = 0;
            String[] files = null;
            cmd = cmdPfx + "dir " + this.directory;
            try {
                String dir = owner.print_line_exec(worker, cmd, owner.writeTimeout);
                files = dir.trim().split("\n");
                numFiles = files.length - 4;
            } catch (Exception e) {
                owner.logError(worker, "Listing "+where+" failed: "+cmd+" :: ", e);
                return;
            }

            owner.traceInfo(worker, "Have "+numFiles+"/"+this.maxFiles+" "+where+" files in "+this.directory);
            if (files == null || numFiles < this.maxFiles) {
                return; // Current limit below max, return early
            }

            // Make a list of the files [date+name], trim start and end
            List<String> fileList = new ArrayList<>();
            try {
                for (int n = 2; n < files.length - 2; n++) {
                    if (files[n].trim().isEmpty()) {
                        continue;
                    }
                    final String[] tokens = files[n].split(" +");
                    if (tokens.length < 8) {
                        owner.logError(worker, "Ignored suspicious "+where+" file: "+files[n]);
                        continue;
                    }
                    // # -<rights> <size> 06:41:25 Sep 17 2018  <filename>
                    SimpleDateFormat in = new SimpleDateFormat("HH:mm:ss MMM dd yyyy");
                    String datebuf = tokens[3]+" "+tokens[4]+" "+tokens[5]+" "+tokens[6];
                    Date date = in.parse(datebuf);
                    fileList.add(Long.toString(date.getTime())+" "+tokens[7]);
                }
            } catch (Exception e) {
                owner.logError(worker, "Creating "+where+" file list failed", e);
                return;
            }

            // Sort the file list on date (oldest first)
            String[] sortedFiles = fileList.toArray(new String[fileList.size()]);
            Arrays.sort(sortedFiles);

            // Delete oldest archive files to keep maximum
            for (int n = 0; n < sortedFiles.length - this.maxFiles; n++) {
                String name = sortedFiles[n].split(" ")[1];
                cmd = cmdPfx + "delete /noconfirm " + this.directory + name;
                try {
                    owner.traceVerbose(worker, "Deleting "+where+" file: "+name);
                    String res = owner.print_line_exec(worker, cmd);
                    if (isDeviceError(res)) {
                        throw new Exception(res);
                    }
                } catch (Exception e) {
                    owner.logError(worker, "Deleting "+where+" file failed: "+cmd+" :: ", e);
                }
            }
        }


        /**
         * Check if device error
         * @param device reply
         * @return true if error, else false
         */
        private boolean isDeviceError(String reply) {
            if (reply.contains("ERROR:") || reply.contains("%Error")) {
                return true;
            } else {
                return false;
            }
        }
    }
}
