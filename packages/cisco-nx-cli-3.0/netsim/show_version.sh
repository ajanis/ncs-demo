#!/bin/bash

cat <<EOF
Cisco Nexus Operating System (NX-OS) Software
TAC support: http://www.cisco.com/tac
Copyright (c) 2002-2011, Cisco Systems, Inc. All rights reserved.
The copyrights to certain works contained in this software are
owned by other third parties and used and distributed under
license. Certain components of this software are licensed under
the GNU General Public License (GPL) version 2.0 or the GNU
Lesser General Public License (LGPL) Version 2.1. A copy of each
such license is available at
http://www.opensource.org/licenses/gpl-2.0.php and
http://www.opensource.org/licenses/lgpl-2.1.php
nexus# show version
Cisco Nexus Operating System (NX-OS) Software
TAC support: http://www.cisco.com/tac
Copyright (c) 2002-2011, Cisco Systems, Inc. All rights reserved.
The copyrights to certain works contained herein are owned by
other third parties and are used and distributed under license.
Some parts of this software are covered under the GNU Public
License. A copy of the license is available at
http://www.gnu.org/licenses/gpl.html.

Software
  loader:    version unavailable [last: loader version not available]
  kickstart: version 4.2(1)SV1(4)
  system:    version 4.2(1)SV1(4)
  kickstart image file is: bootflash:/nexus-1000v-kickstart-mz.4.2.1.SV1.4.bin
  kickstart compile time:  1/27/2011 14:00:00 [01/27/2011 22:26:45]
  system image file is:    bootflash:/nexus-1000v-mz.4.2.1.SV1.4.bin
  system compile time:     1/27/2011 14:00:00 [01/28/2011 00:56:08]


Hardware
  cisco Nexus 1000V Chassis ("Virtual Supervisor Module")
  Intel(R) Core(TM) i3 CPU     with 2075740 kB of memory.
  Processor Board ID T0C29750769

  Device name: nexus
  bootflash:    1557496 kB

Kernel uptime is 38 day(s), 18 hour(s), 18 minute(s), 9 second(s)


plugin
  Core Plugin, Ethernet Plugin, Virtualization Plugin
EOF
