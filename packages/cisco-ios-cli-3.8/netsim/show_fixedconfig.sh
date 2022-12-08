#!/bin/bash

cat <<EOF
Building configuration...



Current configuration : 1747 bytes

!

! Last configuration change at 07:44:06 UTC Fri Jan 30 2015 by Hakan

!

version 15.1

service timestamps debug datetime msec

service timestamps log datetime msec

no platform punt-keepalive disable-kernel-core

!

hostname CE3

!

boot-start-marker

boot system flash bootflash:asr1001-universalk9.V151_3_S3_SR620260181_2.bin

boot-end-marker

!

!

vrf definition Mgmt-intf

 !

 address-family ipv4

 exit-address-family

 !

 address-family ipv6

 exit-address-family

!

enable secret 4 tnhtc92DXBhelxjYk8LWJrPV36S2i4ntXrpb4RFmfqY

!

aaa new-model

!

!

aaa authentication login default enable

!

!

!

!

!

aaa session-id common

!

transport-map type persistent ssh ssh-handler

 authentication-retries 1

 rsa keypair-name sshkeys

 transport interface GigabitEthernet0

!

!

!

!

ip domain name cisco.com

!

!

!

!

!

!

multilink bundle-name authenticated

!

!

!

!

!

!

!

!

!

!

!

redundancy

 mode none

!

!

!

!

!

ip tftp source-interface GigabitEthernet0

!

!

!

!

!

!

!

!

interface Loopback0

 no ip address

!

interface GigabitEthernet0/0/0

 no ip address

 negotiation auto

!

interface GigabitEthernet0/0/1

 ip address 192.168.16.2 255.255.255.0

 negotiation auto

!

interface GigabitEthernet0/0/2

 ip address 192.168.19.1 255.255.255.0

 negotiation auto

!

interface GigabitEthernet0/0/3

 ip address 192.168.20.1 255.255.255.0

 negotiation auto

!

interface GigabitEthernet0

 vrf forwarding Mgmt-intf

 ip address 172.20.160.58 255.255.255.128

 negotiation auto

!

ip forward-protocol nd

!

no ip http server

no ip http secure-server

ip route vrf Mgmt-intf 0.0.0.0 0.0.0.0 172.20.160.1

ip route vrf Mgmt-intf 223.255.254.254 255.255.255.254 1.58.0.1

!

!

!

!

!

control-plane

!

!

!

!

!

line con 0

 stopbits 1

line aux 0

 stopbits 1

line vty 0 4

 exec-timeout 0 0

 password cisco

!

end



CE3#<<EOF
 29-Jan-2015::23:02:02.241 SET-TIMOUT
<<EOF
 29-Jan-2015::23:02:02.241 SHOW
!

! Last configuration change at 07:44:06 UTC Fri Jan 30 2015 by Hakan

!

version 15.1

service timestamps debug datetime msec

service timestamps log datetime msec

no platform punt-keepalive disable-kernel-core

!

hostname CE3

!

!

!

vrf definition Mgmt-intf

 !

 address-family ipv4

 exit-address-family

 !

 address-family ipv6

 exit-address-family

!

enable secret 4 tnhtc92DXBhelxjYk8LWJrPV36S2i4ntXrpb4RFmfqY

!

aaa new-model

!

!

aaa authentication login default enable

!

!

!

!

!

aaa session-id common

!

transport-map type persistent ssh ssh-handler

 authentication-retries 1

 rsa keypair-name sshkeys

 transport interface GigabitEthernet0

!

!

!

!

ip domain name cisco.com

!

!

!

!

!

!

multilink bundle-name authenticated

!

!

!

!

!

!

!

!

!

!

!

redundancy

 mode none

!

!

!

!

!

ip tftp source-interface GigabitEthernet0

!

!

!

!

!

!

!

!

interface Loopback0

 no ip address

!

interface GigabitEthernet0/0/0

 no ip address

 negotiation auto

!

interface GigabitEthernet0/0/1

 ip address 192.168.16.2 255.255.255.0

 negotiation auto

!

interface GigabitEthernet0/0/2

 ip address 192.168.19.1 255.255.255.0

 negotiation auto

!

interface GigabitEthernet0/0/3

 ip address 192.168.20.1 255.255.255.0

 negotiation auto

!

interface GigabitEthernet0

 vrf forwarding Mgmt-intf

 ip address 172.20.160.58 255.255.255.128

 negotiation auto

!

ip forward-protocol nd

!

no ip http server

no ip http secure-server

ip route vrf Mgmt-intf 0.0.0.0 0.0.0.0 172.20.160.1

ip route vrf Mgmt-intf 223.255.254.254 255.255.255.254 1.58.0.1

!

!

!

!

!

control-plane

!

!

!

!

!

line con 0

 stopbits 1

line aux 0

 stopbits 1

line vty 0 4

 exec-timeout 0 0

 password cisco

!


EOF
