
package com.tailf.packages.ned.asa;

import java.io.IOException;

import ch.ethz.ssh2.Session;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.SCPClient;
import ch.ethz.ssh2.SCPOutputStream;

public class ASASCPClient extends SCPClient {
    Connection copyConn;

    public ASASCPClient(Connection conn) {
		super(conn);
        this.copyConn = conn;
	}

    @Override
    public SCPOutputStream put(final String remoteFile, long length, String remoteTargetDirectory, String mode)
        throws IOException
	{
		Session sess = null;

		if (null == remoteFile)
			throw new IllegalArgumentException("Null argument.");
		if (null == remoteTargetDirectory)
			remoteTargetDirectory = "";
		if (null == mode)
			mode = "0600";
		if (mode.length() != 4)
			throw new IllegalArgumentException("Invalid mode.");

		for (int i = 0; i < mode.length(); i++)
			if (Character.isDigit(mode.charAt(i)) == false)
				throw new IllegalArgumentException("Invalid mode.");

		remoteTargetDirectory = (remoteTargetDirectory.length() > 0) ? remoteTargetDirectory : ".";

		String cmd = "scp -v -t " + remoteTargetDirectory + remoteFile;

		sess = this.copyConn.openSession();
		sess.execCommand(cmd, null);  // FIXME?: charsetName);

		return new SCPOutputStream(this, sess, remoteFile, length, mode);
	}
}
