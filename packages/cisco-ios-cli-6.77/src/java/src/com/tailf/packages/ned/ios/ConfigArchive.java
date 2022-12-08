
package com.tailf.packages.ned.ios;

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

    private static final String COPYCMD = "copy running-config ";
    private static final String CONFIRM = " | prompts ENTER";
    private static final String CONFIRM3 = " | prompts ENTER ENTER ENTER";

    private IOSNedCli owner;
    private LinkedList<ArchiveEntry> archiveList = new LinkedList<>();

    /*
     * Constructor
     */
    ConfigArchive(IOSNedCli owner) {
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
     * @throws NedException
     */
    public void init(NedWorker worker) throws NedException {
        List<Map<String,String>> entries;

        entries = owner.nedSettings.getListEntries("write/config-archive");
        for (Map<String,String> entry : entries) {
            String id = entry.get("__key__"); // "id"
            final boolean disabled = "true".equals(entry.get("disabled"));
            String type = entry.get("type");
            String directory = entry.get("directory");
            String filename = entry.get("filename");
            if (directory == null || filename == null) {
                throw new NedException("write/config-archive ned-setting missing directory or filename");
            }
            String remoteUser = null;
            String remotePassword = null;
            int maxFiles = 10;
            if ("remote".equals(type)) {
                remoteUser = entry.get("remote-user");
                remotePassword = entry.get("remote-password");
            } else {
                try {
                    maxFiles = Integer.parseInt(entry.get("max-files"));
                } catch (Exception e) {
                    // Ignore
                }
            }
            owner.traceInfo(worker, "Adding archive "+id);
            ArchiveEntry archive = new ArchiveEntry(id, disabled, type, directory,
                                                    filename, remoteUser, remotePassword,
                                                    maxFiles);
            archiveList.add(archive);
        }
    }


    /**
     * Archive config
     * @param
     * @throws NedException
     */
    public void archive(NedWorker worker) throws NedException {
        if (archiveList.isEmpty()) {
            return;
        }

        // Reconnect to device if remote end closed connection due to being idle
        if (owner.session.serverSideClosed()) {
            owner.reconnectDevice(worker);
        }

        owner.traceInfo(worker, "Arching config...");

        final long start = owner.resetTimeout(worker, owner.writeTimeout, 0);
        try {
            for (int n = 0; n < archiveList.size(); n++) {
                ArchiveEntry entry = archiveList.get(n);
                entry.archive(worker);
            }
        } finally {
            owner.setReadTimeout(worker);
        }

        owner.traceInfo(worker, "Arching config done "+owner.tickToString(start));
    }


    /*
     **************************************************************************
     * Private methods
     **************************************************************************
     */


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
        int maxFiles;

        // ArchiveEntry.Constructor
        ArchiveEntry(String id, boolean disabled, String type, String directory, String filename,
                     String remoteUser, String remotePassword, int maxFiles) {
            this.id = id;
            this.disabled = disabled;
            this.type = type;
            this.directory = directory;
            this.filename = filename.replace("%h", owner.device_id);
            this.remoteUser = remoteUser;
            this.remotePassword = remotePassword;
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
                destination = destination.replace("%i", Integer.toString(owner.lastToHandle));
            }
            destination = this.directory + destination;

            //
            // REMOTE
            //
            if ("remote".equals(type)) {
                owner.traceInfo(worker, "Archiving remotely ["+id+"] "+destination);
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
                String cmd = COPYCMD + destination + CONFIRM3;
                try {
                    if (!owner.logVerbose) {
                        owner.traceInfo(worker, "Sending archive: " + COPYCMD + logdest + "\n");
                        if (owner.trace) {
                            owner.session.setTracer(null);
                        }
                    }
                    String res = owner.nedCommand.runCommand(worker, cmd);
                    if (!owner.logVerbose) {
                        owner.traceInfo(worker, res);
                        if (owner.trace) {
                            owner.session.setTracer(worker);
                        }
                    }
                    if (isDeviceError(res)) {
                        throw new NedException(res);
                    }
                } catch (Exception e) {
                    if (!owner.logVerbose && owner.trace) {
                        owner.session.setTracer(worker);
                    }
                    owner.logError(worker, "Arching remotely failed: "+cmd+" :: ", e);
                }

                return;
            }


            //
            // LOCAL
            //
            owner.traceInfo(worker, "Archiving locally ["+id+"] "+destination);
            archiveLocal(worker, destination);
        }


        /**
         * Save running-config locally
         * @param
         */
        private void archiveLocal(NedWorker worker, String destination) {

            // Save running-config
            String cmd = COPYCMD + destination + CONFIRM;
            try {
                String res = owner.nedCommand.runCommand(worker, cmd);
                if (isDeviceError(res)) {
                    throw new NedException(res);
                }
            } catch (Exception e) {
                owner.logError(worker, "Saving in archive failed: "+cmd+"\n", null);
                return;
            }

            if (this.maxFiles <= 0) {
                return; // No maximum, return early
            }

            //
            // Maintain a maximum of 'cisco-ios write config-archive * max-files' archive files
            //

            // List the files
            int numFiles = 0;
            String[] files = null;
            cmd = "dir " + this.directory;
            try {
                String dir = owner.print_line_exec(worker, cmd, owner.writeTimeout);
                files = dir.trim().split("\n");
                numFiles = files.length - 4;
            } catch (Exception e) {
                owner.logError(worker, "Listing archive failed: "+cmd+" :: ", e);
                return;
            }

            owner.traceInfo(worker, "Have "+numFiles+"/"+this.maxFiles+" archive files in "+this.directory);
            if (files == null || numFiles < this.maxFiles) {
                return; // Current limit below max, return early
            }

            // Make a list of the files [date+name], trim start and end
            List<String> fileList = new ArrayList<>();
            try {
                for (int n = 2; n < files.length - 2; n++) {
                    String[] tokens = files[n].trim().split(" +");
                    if (tokens.length < 9) {
                        owner.logError(worker, "Suspicious archive file: "+files[n]);
                        continue;
                    }
                    // # -<rights> <size> Jun 14 2018 21:16:50 +00:00  <filename>
                    SimpleDateFormat in = new SimpleDateFormat("MMM dd yyyy HH:mm:ss");
                    String datebuf = tokens[3]+" "+tokens[4]+" "+tokens[5]+" "+tokens[6];
                    Date date = in.parse(datebuf);
                    fileList.add(Long.toString(date.getTime())+" "+tokens[8]);
                }
            } catch (Exception e) {
                owner.logError(worker, "Creating archive file list failed", e);
                return;
            }

            // Sort the file list on date (oldest first)
            String[] sortedFiles = fileList.toArray(new String[fileList.size()]);
            Arrays.sort(sortedFiles);

            // Delete oldest archive files to keep maximum
            for (int n = 0; n < sortedFiles.length - this.maxFiles; n++) {
                String name = sortedFiles[n].split(" ")[1];
                cmd = "delete /force " + this.directory + name;
                try {
                    owner.traceVerbose(worker, "Deleting archive file: "+name);
                    String res = owner.print_line_exec(worker, cmd);
                    if (isDeviceError(res)) {
                        throw new NedException(res);
                    }
                } catch (Exception e) {
                    owner.logError(worker, "Deleting archive file failed: "+cmd+" :: ", e);
                }
            }
        }


        /**
         * Check if device error
         * @param device reply
         * @return true if error, else false
         */
        private boolean isDeviceError(String reply) {
            return (reply.contains("Unknown command")
                    || reply.contains("Invalid input")
                    || reply.contains("%Error"));
        }
    }
}
