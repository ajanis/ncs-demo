#!/bin/bash

cat <<EOF

Thu Mar 16 19:47:53.688 CST

Install operation 37 '(admin) install add source /ftp://csm:******@10.89.188.234/ asr9k-p-4.2.3.CSCud37351.pie synchronous' started by user 'admin' via CLI at 19:47:53 CST Thu Mar 16 2017.

Error:    Cannot proceed with the add operation because the pie '/ftp://csm:******@10.89.188.234/asr9k-p-4.2.3.CSCud37351.pie' could not be found.

Error:    Suggested steps to resolve this:

  *** output 16-Mar-2017::18:47:54.466 ***


  *** input 16-Mar-2017::18:47:54.466 ***

Error:     - check the name of the pie.

Error:     - check the location of the pie.

Error:     - check the file permissions of the pie.

Error:     - check network connectivity of the SDR containing the dSC.

/ 100% complete: The operation can no longer be aborted (ctrl-c for options)
[2KThe operation can no longer be aborted. Continue operating synchronously or operate asynchronously (sync/async)? [sync]
EOF
