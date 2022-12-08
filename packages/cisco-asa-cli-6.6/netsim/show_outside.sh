#!/bin/bash

cat <<EOF
Interface GigabitEthernet0/1 "outside", is up, line protocol is up
  Hardware is i82540EM rev03, BW 1000 Mbps, DLY 10 usec
        Auto-Duplex(Full-duplex), Auto-Speed(1000 Mbps)
        Input flow control is unsupported, output flow control is off
        MAC address fa16.3e49.7676, MTU 1500
        IP address 83.131.11.162, subnet mask 255.255.255.224
        5784 packets input, 0 bytes, 0 no buffer
        Received 0 broadcasts, 0 runts, 0 giants
        0 input errors, 0 CRC, 0 frame, 0 overrun, 0 ignored, 0 abort
        0 pause input, 0 resume input
        551 L2 decode drops
        4962 packets output, 0 bytes, 0 underruns
        0 pause output, 0 resume output
        0 output errors, 0 collisions, 1 interface resets
        0 late collisions, 0 deferred
        10 input reset drops, 0 output reset drops
        input queue (blocks free curr/low): hardware (463/459)
        output queue (blocks free curr/low): hardware (511/506)
  Traffic Statistics for "outside":
        5223 packets input, 596214 bytes
        4962 packets output, 331039 bytes
        4805 packets dropped
      1 minute input rate 6 pkts/sec,  848 bytes/sec
      1 minute output rate 6 pkts/sec,  453 bytes/sec
      1 minute drop rate, 6 pkts/sec
      5 minute input rate 7 pkts/sec,  887 bytes/sec
      5 minute output rate 7 pkts/sec,  479 bytes/sec
      5 minute drop rate, 7 pkts/sec
  Control Point Interface States:
        Interface number is 4
        Interface config status is active
        Interface state is active
EOF

