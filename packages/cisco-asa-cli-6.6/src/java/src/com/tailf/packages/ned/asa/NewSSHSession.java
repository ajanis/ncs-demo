/*     Java -*-
 *
 *  Copyright 2011 Tail-F Systems AB. All rights reserved.
 *
 *  This software is the confidential and proprietary
 *  information of Tail-F Systems AB.
 *
 *  $Id$
 *
 */

package com.tailf.packages.ned.asa;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import ch.ethz.ssh2.ChannelCondition;
import ch.ethz.ssh2.Connection;
import ch.ethz.ssh2.Session;

import com.tailf.ned.NedWorker;
import com.tailf.ned.NedTracer;
import com.tailf.ned.CliSession;
import com.tailf.ned.NedExpectResult;
import com.tailf.ned.SSHSessionException;
import com.tailf.ned.TraceBuffer;

/**
 * A SSH  transport.
 * This class uses the Ganymed SSH implementation.
 * (<a href="http://www.ganymed.ethz.ch/ssh2/">
 *     http://www.ganymed.ethz.ch/ssh2/</a>)
 * <p>
 * Example:
 * <pre>
 * SSHConnection c = new SSHConnection("127.0.0.1", 22);
 * c.authenticateWithPassword("ola", "secret");
 * SSHSession ssh = new SSHSession(c);
 * </pre>
 *
 */
public class NewSSHSession implements CliSession {

    private String conn = null;
    private Connection connection = null;
    private Session session = null;

    private BufferedReader in = null;
    private PrintWriter out = null;
    protected int readTimeout = 0;  // millisecs
    private NedTracer tracer;
    private TraceBuffer traceInBuf = null;

    //private static Logger LOGGER = Logger.getLogger(ASANedCli.class);

    private StringBuilder line = new StringBuilder();

    /**
     * Constructor with an extra argument for a readTimeout timer.
     *
     * @param con
     * @param readTimeout Time to wait for read. (in milliseconds)
     * @param width Terminal width (in characters)
     * @param height Terminal height (in characters)
     */
    public NewSSHSession(Connection con, int readTimeout, NedTracer tracer,
                               String conn, int width, int height)
        throws IOException {
        this.readTimeout = readTimeout;
        this.connection = con;
        this.tracer = tracer;
        this.conn = conn;
        if (tracer != null && conn != null) {
            this.traceInBuf = new TraceBuffer(100, "in", conn);
        }

        init(con, width, height);
    }

    private void init(Connection con, int width, int height)
        throws IOException {
        session = con.openSession();
        session.requestPTY("dumb", width, height, 0, 0, null);
        session.startShell();

        InputStream is = session.getStdout();
        OutputStream os = session.getStdin();
        in = new BufferedReader(new InputStreamReader(is));
        out = new PrintWriter(os, false);
        // hello will be done by NetconfSession
    }

    /**
     * Return the underlying ssh connection object
     */

    public Connection getSSHConnection() {
        return connection;
    }

    /**
     * Return the readTimeout value that is used to read data from
     * the ssh socket. If a read doesn't complete within the stipulated
     * timeout an INMException is thrown *
     */

    public int getReadTimeout() {
        return readTimeout;
    }

    /**
     * Set the read timeout
     * @param readTimeout timeout in milliseconds
     * The readTimeout parameter affects all read operations. If a timeout
     * is reached, an INMException is thrown. The socket is not closed.
     */

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    /**
     * Tell whether this transport is ready to be read.
     * @return true if there is something to read, false otherwise.
     * This function can typically be used to poll a socket and see
     * there is data to be read. The function will also return true
     * if the server side has closed its end of the ssh socket.
     * To explicitly just check for that, use the serverSideClosed()
     * method.
     */

    public boolean ready() throws IOException {
        return ready(1);
    }

    public boolean ready(int timeout) throws IOException {
        if (in.ready()) {
            return true;
        }
        int conditionSet = session.waitForCondition(0xffffffff, timeout);
        if ((conditionSet & ChannelCondition.TIMEOUT) ==
            ChannelCondition.TIMEOUT) {
            // It's a timeout
            return false;
        }
        return true;
    }


    /**
     * given a live NewSSHSession, check if the server side has
     * closed it's end of the ssh socket
     */

    public boolean serverSideClosed() {
        int conditionSet =
            ChannelCondition.TIMEOUT |
            ChannelCondition.CLOSED |
            ChannelCondition.EOF;
        try {
            conditionSet = session.waitForCondition(conditionSet, 1);
        } catch (IOException e) {
            return true;
        }
        if ((conditionSet & ChannelCondition.TIMEOUT) ==
            ChannelCondition.TIMEOUT) {
            // it's a timeout
            return false;
        }
        return true;
    }


    /**
     * If we have readTimeout set, and an outstanding operation was
     * timed out - the socket may still be alive.
     * @return number of discarded characters
     */

    public int readUntilWouldBlock() {
        int ret = 0;
        int c;
        while (true)
            try {
                if (!(ready())) {
                    return ret;
                }
                c = in.read();
                if (c == -1) {
                    return ret;
                }
                ret++;
            } catch (IOException e) {
                return ret;
            }
    }

    /**
     * Read from socket until Pattern is encountered.
     * @return the characters read.
     * @param Str is a string which is match against each line read.
     * @param include controls if the pattern should be include
     * in the returned string or not.
     */

    public String expectStr(String Str, boolean include, int timeout)
        throws SSHSessionException, IOException {
        StringBuilder buffer = new StringBuilder();

        // First try with what we have left in line
        {
            String linestr = line.toString();

            int hit = linestr.indexOf(Str);

            if (hit != -1) {
                // found match
                int end = hit+Str.length();
                String matching = line.subSequence(0, end).toString();

                if (include) {
                    buffer.append(matching);
                }

                line.delete(0, end);

                return buffer.toString();
            }
        }

        // else extend line

        while(true) {
            if (timeout > 0 && !ready(timeout)) {
                if (tracer != null && conn != null) {
                    tracer.trace(line.toString(), "in", conn);
                }
                throw new SSHSessionException(SSHSessionException.READ_TIMEOUT, "read timeout");
            }
            int i;

            if ((i = in.read()) == -1) {
                //EOF
                if (tracer != null && conn != null) {
                    tracer.trace(line.toString(), "in", conn);
                }
                throw new SSHSessionException(SSHSessionException.READ_EOF, "read eof");
            }
            char c = (char) i;
            buffer.append(c);

            // read while there are chars in the buffer and we
            // don't find a newline or end of line
            while(in.ready() && (c != '\n') && (c != '\r')) {
                i = in.read();
                if (i == -1) {
                    break;
                }
                c = (char) i;
                line.append(c);
            }

            String linestr = line.toString();

            int hit = linestr.indexOf(Str);

            if (hit != -1) {
                // found match
                int end = hit+Str.length();
                String matching = line.subSequence(0, end).toString();

                if (include) {
                    buffer.append(matching);
                }

                line.delete(0, end);

                return buffer.toString();
            }

            if (c == '\n' || c == '\r') {
                buffer.append(line);
                line.setLength(0);
            }
        }
    }

    /**
     * Read from socket until Pattern is encountered.
     * @return the characters read.
     * @param Str is a regular expression pattern which is match against
     * each line read.
     */

    public String expect(String Str)
        throws SSHSessionException, IOException {
        return expect(Str, false, this.readTimeout);
    }

    public String expect(Pattern p)
        throws SSHSessionException, IOException {
        return expect(p, false, this.readTimeout);
    }


    public String expect(String Str, NedWorker worker)
        throws SSHSessionException, IOException {
        return expect(Str, false, this.readTimeout, worker);
    }

    public String expect(Pattern p, NedWorker worker)
        throws SSHSessionException, IOException {
        return expect(p, false, this.readTimeout, worker);
    }

    /**
     * Read from socket until Pattern is encountered.
     * @return the characters read.
     * @param Str is a regular expression pattern which is match against
     * each line read.
     * @param timeout indicates the read timeout
     */

    public String expect(String Str, int timeout)
        throws SSHSessionException, IOException {
        return expect(Str, false, timeout);
    }

    public String expect(String Str, int timeout, NedWorker worker)
        throws SSHSessionException, IOException {
        return expect(Str, false, timeout, worker);
    }

    /**
     * Read from socket until Pattern is encountered.
     * @return the characters read.
     * @param Str is a regular expression pattern which is match against
     * each line read.
     * @param Include controls if the pattern should be include
     * in the returned string or not.
     */

    public String expect(String Str, boolean Include, int timeout)
        throws SSHSessionException, IOException {
        Pattern p = Pattern.compile(Str);
        return expect(p, Include, timeout);
    }

    public String expect(Pattern p, boolean include, int timeout)
        throws SSHSessionException, IOException {

        NedExpectResult res = expect(new Pattern[] { p }, include, timeout);

        return res.getText();
    }

    public NedExpectResult expect(String[] Str)
        throws SSHSessionException, IOException {
        return expect(Str, false, this.readTimeout);
    }

    public NedExpectResult expect(Pattern[] p)
        throws SSHSessionException, IOException {
        return expect(p, false, this.readTimeout);
    }

    public NedExpectResult expect(String[] Str, boolean include, int timeout)
        throws SSHSessionException, IOException {
        Pattern[] p = new Pattern[Str.length];

        for (int i = 0; i < Str.length ; i++) {
            p[i] = Pattern.compile(Str[i]);
        }

        return expect(p, include, timeout);
    }

    public NedExpectResult expect(Pattern[] p, boolean include, int timeout)
        throws SSHSessionException, IOException {
        return expect(p, include, timeout, false);
    }

    public String expect(String Str, boolean Include, int timeout,
                         NedWorker worker)
        throws SSHSessionException, IOException {
        Pattern p = Pattern.compile(Str);
        return expect(p, Include, timeout, worker);
    }

    public String expect(Pattern p, boolean include, int timeout,
                         NedWorker worker)
        throws SSHSessionException, IOException {

        NedExpectResult res = expect(new Pattern[] { p }, include,
                                     timeout, worker);

        return res.getText();
    }

    public NedExpectResult expect(String[] Str, NedWorker worker)
        throws SSHSessionException, IOException {
        return expect(Str, false, this.readTimeout, worker);
    }

    public NedExpectResult expect(Pattern[] p, NedWorker worker)
        throws SSHSessionException, IOException {
        return expect(p, false, this.readTimeout, worker);
    }

    public NedExpectResult expect(String[] Str, boolean include,
                                  int timeout, NedWorker worker)
        throws SSHSessionException, IOException {
        Pattern p[] = new Pattern[Str.length];

        for (int i=0 ; i < Str.length ; i++) {
            p[i] = Pattern.compile(Str[i]);
        }

        return expect(p, include, timeout, worker);
    }

    public NedExpectResult expect(Pattern[] p, boolean include,
                                  int timeout, NedWorker worker)
        throws SSHSessionException, IOException {
        return expect(p, include, timeout, false, worker);
    }

    public NedExpectResult expect(Pattern[] p, boolean include, int timeout,
                                  boolean full)
        throws SSHSessionException, IOException {
        return expect(p, include, timeout, full, null);
    }

    public NedExpectResult expect(Pattern[] p, boolean include, int timeout,
                                  boolean full, NedWorker worker)
        throws SSHSessionException, IOException {
        StringBuilder buffer = new StringBuilder();
        int i;
        int end=0;

        long lastTime = System.currentTimeMillis();
        long time;

        // First, see if we have a hit with what we got in line already

        {
            boolean hit=false;

            String linestr = line.toString();

            for (i = 0; i < p.length; i++) {
                Matcher m=p[i].matcher(linestr);
                if (full) {
                    if (m.matches()) {
                        hit = true;
                        end = m.end();
                        break;
                    }
                }
                else {
                    if (m.find()) {
                        hit = true;
                        end = m.end();
                        break;
                    }
                }
            }

            if (hit) {
                // found match
                String matching = line.subSequence(0, end).toString();
                if (tracer != null && conn != null) {
                    tracer.trace(matching, "in", conn);
                }
                //if (LOGGER.isInfoEnabled()) {
                //LOGGER.info("expect HIT "+matching);
                //}
                if (include) {
                    buffer.append(matching);
                }
                line.delete(0, end);
                return new NedExpectResult(i, buffer.toString(), matching);
            }
        }

        // else try to extend line

        while(true) {
            time = System.currentTimeMillis();

            if ((time-lastTime) > (0.5 * this.readTimeout) && worker != null) {
                // request more time
                lastTime = time;
                worker.setTimeout(readTimeout);
            }

            if (timeout > 0 && !ready(timeout)) {
                if (tracer != null && traceInBuf != null) {
                    traceInBuf.flush(tracer);
                }
                throw new SSHSessionException(
                    SSHSessionException.READ_TIMEOUT,
                    "read timeout");
            }

            if ((i = in.read()) == -1) {
                //EOF
                if (tracer != null && traceInBuf != null) {
                    traceInBuf.flush(tracer);
                }
                throw new SSHSessionException(
                                              SSHSessionException.READ_EOF,
                                              "read eof");
            }

            char c = (char) i;
            line.append(c);

            // read while there are chars in the buffer and we
            // don't find a newline or end of line
            while(in.ready() && (c != '\n') && (c != '\r')) {
                i = in.read();
                if (i == -1) {
                    break;
                }
                c = (char) i;
                line.append(c);
            }

            boolean hit=false;

            String linestr = line.toString();

            // ignore empty lines
            if (linestr.length() > 1 || (c != '\n' && c != '\r')) {
                for (i = 0; i < p.length; i++) {
                    Matcher m=p[i].matcher(linestr);
                    if (full) {
                        if (m.matches()) {
                            hit = true;
                            end = m.end();
                            break;
                        }
                    }
                    else {
                        if (m.find()) {
                            hit = true;
                            end = m.end();
                            break;
                        }
                    }
                }
            }

            if (hit) {
                // found match
                if (tracer != null && traceInBuf != null) {
                    traceInBuf.flush(tracer);
                }
                String matching = line.subSequence(0, end).toString();
                if (tracer != null && conn != null) {
                    tracer.trace(matching, "in", conn);
                }
                //LOGGER.info("expect HIT "+matching);

                if (include) {
                    buffer.append(matching);
                }
                line.delete(0, end);
                return new NedExpectResult(i, buffer.toString(), matching);
            }

            if (c == '\n' || c == '\r') {
                //LOGGER.info("expect "+line);
                if (tracer != null && traceInBuf != null) {
                    traceInBuf.append(tracer, line.toString());
                }
                buffer.append(line);
                line.setLength(0);
            }
        }
    }


    /**
     * Read from socket until Pattern is encountered.
     * @return the characters read.
     * @param str is a regular expression pattern which is match against
     * each line read.
     * @param include controls if the pattern should be include
     * in the returned string or not.
     * @param full controls if the pattern should be matched against
     * the entire line, or if a positive match is accepted whenever the
     * pattern matches any substring on a line.
     */

    public String expect(String str, boolean include,
                         boolean full, int timeout)
        throws IOException, SSHSessionException {

        return expect(str, include, full, timeout, null);
    }


    public String expect(String str, boolean include,
                         boolean full, int timeout, NedWorker worker)
        throws SSHSessionException, IOException {

        Pattern p = Pattern.compile(str);
        NedExpectResult res = expect(new Pattern[] { p }, include,
                                     timeout, full, worker);
        return res.getText();
    }

    /**
     * Prints an integer (as text) to the output stream.
     * @param iVal Text to send to the stream.
     */
    public void print(int iVal) {
        if (tracer != null && conn != null) {
            tracer.trace(Integer.toString(iVal), "out", conn);
        }
        out.print(iVal);
        out.flush();
    }

    /**
     * Prints text to the output stream.
     * @param s Text to send to the stream.
     */
    public void print(String s) {
        if (tracer != null && conn != null) {
            tracer.trace(s, "out", conn);
        }
        out.print(s);
        out.flush();
    }

    /**
     * Prints an integer (as text) to the output stream.
     * A newline char is appended to end of the output stream.
     * @param iVal Text to send to the stream.
     */
    public void println(int iVal) {
        if (tracer != null && conn != null) {
            tracer.trace(Integer.toString(iVal)+"\n", "out", conn);
        }
        out.println(iVal);
        out.flush();
    }

    /**
     * Print text to the output stream.
     * A newline char is appended to end of the output stream.
     * @param s Text to send to the stream.
     */
    public void println(String s) {
        if (tracer != null && conn != null) {
            tracer.trace(s+"\n", "out", conn);
        }
        out.println(s);
        out.flush();
    }


    /**
     * Signals that the final chunk of data has be printed to the output
     * transport stream. This method furthermore flushes the transport
     * output stream buffer.
     */
    public void flush() {
        out.flush();
    }

    /**
     * Needed by users that need to monitor a session for EOF .
     * This will return the underlying Ganymed SSH Session object.
     *
     * The ganymed Session object has a method waitForCondition()
     * that can be used to check the connection state of an ssh socket.
     * Assuming a A Session object s:
     * <pre>
     * int conditionSet =
     *     ChannelCondition.TIMEOUT ;amp
     *     ChannelCondition.CLOSED ;amp
     *     ChannelCondition.EOF;
     *     conditionSet = s.waitForCondition(conditionSet, 1);
     *  if (conditionSet != ChannelCondition.TIMEOUT) {
     *      // We know the server closed it's end of the ssh
     *      // socket
     * </pre>
     */


    public Session getSession() {
        return session;
    }


    public void setTracer(NedTracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Closes the SSH connection, including all sessions (only one in
     * this case, compared to many in Netconf)
     */
    public void close() {
        // We rely on the connection being closed here
        connection.close();
        session.close();
        try {
            in.close();
        } catch (IOException e) {
            // Ignore exception
        }
        out.close();
    }

}
