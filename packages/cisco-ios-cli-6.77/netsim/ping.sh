#!/bin/bash

ip=${@: -1}

fail=

# Fail if vrf name is "error"
if [ "$1" = "--vrf" ]; then
    if [ "$2" = "error" ]; then
        fail=1
    fi
fi

# Fail if ip address begins with 254
if [ "${ip:0:3}" = "254" ]; then
    fail=1
fi

if [ -n "$fail" ]; then
    cat <<EOF
Type escape sequence to abort.
Sending 5, 100-byte ICMP Echos to $ip, timeout is 2 seconds:
.....
Success rate is 0 percent (0/5)
EOF
else
    cat <<EOF
Type escape sequence to abort.
Sending 5, 100-byte ICMP Echos to $ip, timeout is 2 seconds:
!!!!!
Success rate is 100 percent (5/5), round-trip min/avg/max = 20/20/21 ms
EOF
fi
