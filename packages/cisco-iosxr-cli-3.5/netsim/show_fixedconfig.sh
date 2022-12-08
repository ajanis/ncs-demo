#!/bin/bash

cat <<EOF
Building configuration...
!! IOS XR Configuration 4.2.3
!! Last configuration change at Sun Oct 12 07:41:47 2014 by la
!
version 12.2
no service pad
service timestamps debug datetime msec localtime show-timezone
service timestamps log datetime msec localtime show-timezone
service password-encryption
service pt-vty-logging
service counters max age 10
no service dhcp
service unsupported-transceiver
!
hostname IPLTINSDC6003
!
!
logging buffered informational
no logging console
enable secret 5 $1$zpIa$9j6wyk5b44Es14ZUY9p710
!
aaa new-model
!
!
aaa authentication login conslogin group tacacs+ local
aaa authentication login vtylogin group tacacs+ local
aaa authentication enable default group tacacs+ enable
aaa authorization console
aaa authorization config-commands
aaa authorization exec default group tacacs+ local
aaa authorization commands 0 default group tacacs+ if-authenticated
aaa authorization commands 1 default group tacacs+ if-authenticated
aaa authorization commands 15 default group tacacs+ if-authenticated
aaa accounting exec default start-stop group tacacs+
aaa accounting commands 0 default start-stop group tacacs+
aaa accounting commands 1 default start-stop group tacacs+
aaa accounting commands 15 default start-stop group tacacs+
!
!
!
!
!
aaa session-id common
clock timezone MST -7
clock summer-time MDT recurring
logging event link-status default
ip subnet-zero
no ip source-route
!
!
no ip bootp server
ip domain name twtelecom.com
ip name-server 10.1.22.55
!
!
no ip igmp snooping
!
!
vtp mode transparent
rep admin vlan 501
mls ip multicast flow-stat-timer 9
mls flow ip interface-full
no mls qos rewrite ip dscp
mls qos
mls cef error action reset
multilink bundle-name authenticated
!
!
file verify auto
!
spanning-tree mode mst
spanning-tree extend system-id
!
spanning-tree mst configuration
 name IPLTINSDC6001
 instance 1 vlan 2-275
 instance 2 vlan 276-550
 instance 3 vlan 551-825
 instance 4 vlan 826-1001,1025-1125
 instance 5 vlan 1126-1400
 instance 6 vlan 1401-1675
 instance 7 vlan 1676-1950
 instance 8 vlan 1951-2225
 instance 9 vlan 2226-2500
 instance 10 vlan 2501-2775
 instance 11 vlan 2776-3050
 instance 12 vlan 3051-3325
 instance 13 vlan 3326-3600
 instance 14 vlan 3601-3875
 instance 15 vlan 3876-4094
!
spanning-tree mst 0-15 priority 8192
no errdisable detect cause gbic-invalid
errdisable recovery cause bpduguard
errdisable recovery cause security-violation
errdisable recovery cause channel-misconfig
errdisable recovery cause pagp-flap
errdisable recovery cause dtp-flap
errdisable recovery cause link-flap
errdisable recovery cause l2ptguard
errdisable recovery cause psecure-violation
errdisable recovery interval 30
port-channel load-balance src-dst-port
username dsg privilege 15 secret 5 $1$Qka3$9f0nHNuSwZDJFwlHG5LR7.
!
redundancy
 main-cpu
  auto-sync running-config
  auto-sync config-register
  auto-sync bootvar
 mode sso
!
vlan internal allocation policy ascending
vlan access-log ratelimit 2000
!
vlan 16
!
vlan 22
 name 14/KEFN/102855/TWCS
!
vlan 27
 name 14/KEFN/102911/TWCS
!
vlan 30
 name 14/KFFN/102919/TWCS
!
vlan 35-36,38,40-42,44,46-48
!
vlan 49
 name 14/KEFN/103021/TWCS
!
vlan 50-51
!
vlan 56
 name 14/KEFN/103177/TWCS
!
vlan 65
 name 14/KEFN/102733/TWCS
!
vlan 67,70
!
vlan 83
 name 14/KEFN/102555/TWCS
!
vlan 84
 name 14/KEFN/102556/TWCS
!
vlan 85
 name 14/KEFN/102557/TWCS
!
vlan 99
 name 14/KEFN/103220/TWCS
!
vlan 106
!
vlan 115
 name 14/VLXX/102755/TWCS
!
vlan 122
 name 14/KEFN/108831/TWCS
!
vlan 128
 name 14/KFFN/106609/TWCS
!
vlan 129
 name 14/KEFN/103221/TWCS
!
vlan 142
 name 14/KEFN/103282/TWCS
!
vlan 157,167-168
!
vlan 172
 name 14/VLXX/103512/TWCS
!
vlan 184
 name 14/KDFN/103545/TWCS
!
vlan 236
 name 14/VLXX/104722/TWCS
!
vlan 284-285
!
vlan 288
 name 14/KEFN/104112/TWCS
!
vlan 290
!
vlan 304
 name 14/VLXX/103654/TWCS
!
vlan 306
 name 14/KFFN/104166/TWCS
!
vlan 319
 name 14/KEFN/106818/TWCS
!
vlan 321
 name 14/KDFN/104240/TWCS
!
vlan 339,384,399
!
vlan 403
 name 14/KDFN/104991/TWCS
!
vlan 404,406
!
vlan 411
 name 14/KEFN/106695/TWCS
!
vlan 413
!
vlan 448
 name 14/KEFN/106791/TWCS
!
vlan 461
 name 14/KEFN/106970/TWCS
!
vlan 471
!
vlan 474
 name 14/KEFN/108503/TWCS
!
vlan 490
 name 14/KEFN/107355/TWCS
!
vlan 493
!
vlan 501
 name REP_ADMIN
!
vlan 502
 name SITEBOSS_MGMT
!
vlan 505
 name ADVA_MGMT
!
vlan 506
 name L2_Performance
!
vlan 512
!
vlan 518
 name 14/KEFN/108313/TWCS
!
vlan 522,543-544
!
vlan 551
 name 14/KEFN/102184/TWCS
!
vlan 552
 name 14/KEFN/102183/TWCS
!
vlan 559
 name 14/KEFN/102089/TWCS
!
vlan 571
 name 14/KEFN/108570/TWCS
!
vlan 574
 name 14/KEFN/101862/TWCS
!
vlan 578
 name 14/KEFN/108647/TWCS
!
vlan 589
 name 14/KDFN/101760/TWCS
!
vlan 596
 name 14/KEFN/103324/TWCS
!
vlan 607
 name 14/KEFN/107007/TWCS
!
vlan 608
!
vlan 616
 name VLAN_616
!
vlan 625
!
vlan 631
 name 14/KFFN/102779/TWCS
!
vlan 637
 name 14/KEFN/101185/TWCS
!
vlan 645
 name 14/KEFN/107158/TWCS
!
vlan 651
 name 14/KEFN/107184/TWCS
!
vlan 656
 name 14/KEFN/103741/TWCS
!
vlan 661
 name 14/KEFN/108700/TWCS
!
vlan 664
!
vlan 669
 name 14/KEFN/108670/TWCS
!
vlan 672
 name 14/KEFN/107957/TWCS
!
vlan 673
 name 14/KDFN/100838/TWCS
!
vlan 677
 name 14/KEFN/100770/TWCS
!
vlan 678
 name 14/KEFN/108387/TWCS
!
vlan 687
!
vlan 688
 name 14/KFFN/100666/TWCS
!
vlan 694
 name 14/KFFN/107234/TWCS
!
vlan 699
 name 14/KDFN/107230/TWCS
!
vlan 700
 name 14/KEFN/102057/TWCS
!
vlan 710
 name 14/KDGS/006642/TWCS
!
vlan 730
 name 14/KEFN/103446/TWCS
!
vlan 749
 name 14/KEFN/108497/TWCS
!
vlan 758,804
!
vlan 810
 name 14/KDGS/005733/TWCS
!
vlan 814
 name 14/KEFN/108382/TWCS
!
vlan 818
!
vlan 828
 name 14/KEFN/103069/TWCS
!
vlan 839
 name 14/KEGS/005477/TWCS
!
vlan 845
 name 14/KEFN/107336/TWCS
!
vlan 861
 name 14/KFFN/005186/TWCS
!
vlan 863
 name 14/KEGS/005185/TWCS
!
vlan 867
!
vlan 885
 name 14/KEFN/102975/TWCS
!
vlan 920
 name exfo_test
!
vlan 947
!
vlan 952
 name 14/KDFN/107455/TWCS
!
vlan 953
 name 14/KEFN/102873/TWCS
!
vlan 969
 name 14/KEFN/103718/TWCS
!
vlan 995
 name 14/KDFN/105679/TWCS
!
vlan 1054
 name 14/KEFN/104450/TWCS
!
vlan 1060
!
vlan 1079
 name 14/KDFN/104903/TWCS
!
vlan 1080
!
vlan 1091
 name 14/KEFN/105068/TWCS
!
vlan 1114
 name 14/KEFN/105226/TWCS
!
vlan 1142
!
vlan 1167
 name 14/KDFN/105868/TWCS
!
vlan 1168
 name 14/KEFN/105870/TWCS
!
vlan 1178
 name 14/KDFN/108437/TWCS
!
vlan 1190
 name 14/KEFN/105915/TWCS
!
vlan 1195
 name 14/KEFN/107538/TWCS
!
vlan 1200
 name 14/KEFN/108439/TWCS
!
vlan 1201
 name 14/KEFN/107603/TWCS
!
vlan 1202
 name 14/KEFN/108921/TWCS
!
vlan 1204
 name 14/KEFN/105969/TWCS
!
vlan 1208
 name 14/KDFN/108932/TWCS
!
vlan 1227
!
vlan 1233
 name 14/KFFN/106067/TWCS
!
vlan 1236
 name 14/KEFN/107689/TWCS
!
vlan 1241
 name 14/KEFN/106096/TWCS
!
vlan 1243,1247
!
vlan 1249
 name 14/KEFN/106079/TWCS
!
vlan 1252
 name 14/KDFN/106119/TWCS
!
vlan 1253-1254,1269
!
vlan 1290
 name 14/KEFN/106259/TWCS
!
vlan 1296,1316,1328
!
vlan 1330
 name 14/VLXX/106354/TWCS
!
vlan 1338
 name vlan1338
!
vlan 1357
!
vlan 1358
 name 14/KEFN/106446/TWCS
!
vlan 1361
!
vlan 1366
 name 14/KDFN/106466/TWCS
!
vlan 1367,1370,1372,1393,1403
!
vlan 1421
 name 14/KGFN/106729/TWCS
!
vlan 1425
 name 14/KEFN/106742/TWCS
!
vlan 1433
 name 14/KEFN/108395/TWCS
!
vlan 1434
 name 14/KEFN/106807/TWCS
!
vlan 1446
!
vlan 1450
 name 14/KEFN/106810/TWCS
!
vlan 1458
 name 14/KEFN/106940/TWCS
!
vlan 1462,1471
!
vlan 1472
 name 14/KDFN/106981/TWCS
!
vlan 1473
 name 14/KDFN/106983/TWCS
!
vlan 1480
 name 14/KEFN/106996/TWCS
!
vlan 1481
 name 14/VLXX/107034/TWCS
!
vlan 1482
!
vlan 1485
 name 14/KEFN/107819/TWCS
!
vlan 1492
 name 14/KEFN/107086/TWCS
!
vlan 1497
 name 14/KEFN/107121/TWCS
!
vlan 1499-1500
!
vlan 1505
 name 14/KEFN/107155/TWCS
!
vlan 1506
!
vlan 1511
 name 14/KEFN/107203/TWCS
!
vlan 1512
 name 14/KEFN/107204/TWCS
!
vlan 1520
 name 14/VLXX/107324/TWCS
!
vlan 1531
 name 14/KEFN/107407/TWCS
!
vlan 1535
 name 14/KFFN/107418/TWCS
!
vlan 1544
 name 14/KEFN/107548/TWCS
!
vlan 1546
 name 13/VPXX/103980/TWCS
!
vlan 1551
!
vlan 1559
 name 14/KFFN/107569/TWCS
!
vlan 1562
 name 14/KEFN/107583/TWCS
!
vlan 1563
 name 14/KEFN/107582/TWCS
!
vlan 1570-1571
!
vlan 1573
 name 14/KEFN/107742/TWCS
!
vlan 1578
 name 14/KEFN/108021/TWCS
!
vlan 1584
 name 14/KEFN/107950/TWCS
!
vlan 1596
!
vlan 1609
 name 14/KEFN/108020/TWCS
!
vlan 1610
 name 14/KEFN/107602/TWCS
!
vlan 1614,1617,1623
!
vlan 1624
 name 14/KEFN/107659/TWCS
!
vlan 1625
 name 14/KEFN/107657/TWCS
!
vlan 1640-1642
!
vlan 1643
 name 14/KDFN/107749/TWCS
!
vlan 1645
 name 14/KEFN/107751/TWCS
!
vlan 1647
!
vlan 1651
 name 14/KFFN/108024/TWCS
!
vlan 1661
 name 14/KEFN/107786/TWCS
!
vlan 1662
 name 14/KEFN/107785/TWCS
!
vlan 1665
 name 14/KEFN/107799/TWCS
!
vlan 1671
 name 14/KEFN/107842/TWCS
!
vlan 1673
 name 14/KEFN/107814/TWCS
!
vlan 1679
 name 14/KEFN/107812/TWCS
!
vlan 1681
 name 14/KEFN/107810/TWCS
!
vlan 1682
 name 14/KEFN/107813/TWCS
!
vlan 1683
 name 14/KEFN/107809/TWCS
!
vlan 1690
!
vlan 1693
 name 14/KEFN/107900/TWCS
!
vlan 1694
 name 14/KEFN/107891/TWCS
!
vlan 1695
 name 14/KEFN/107903/TWCS
!
vlan 1696
 name 14/KEFN/107892/TWCS
!
vlan 1704
!
vlan 1716
 name 14/KEFN/107958/TWCS
!
vlan 1721
!
vlan 1722
 name 14/KEFN/107968/TWCS
!
vlan 1724
!
vlan 1730
 name 14/KEFN/108002/TWCS
!
vlan 1731
 name 14/KEFN/108034/TWCS
!
vlan 1738
 name 14/KEFN/108056/TWCS
!
vlan 1740
!
vlan 1742
 name 14/KEFN/107992/TWCS
!
vlan 1743
 name 14/KEFN/108014/TWCS
!
vlan 1748
 name 14/KEFN/108068/TWCS
!
vlan 1750
!
vlan 1753
 name 14/KEFN/108071/TWCS
!
vlan 1755
 name 14/KEFN/108274/TWCS
!
vlan 1756
 name 14/KEFN/108287/TWCS
!
vlan 1762
 name 14/KEFN/108288/TWCS
!
vlan 1764
 name 14/KEFN/108290/TWCS
!
vlan 1766
 name VLXX/187882/TWCS
!
vlan 1767
 name 14/KEFN/108278/TWCS
!
vlan 1768
 name 14/KEFN/108281/TWCS
!
vlan 1769
 name 14/KEFN/108279/TWCS
!
vlan 1778-1779
!
vlan 1780
 name 14/KEFN/108334/TWCS
!
vlan 1781
 name 14/KGFN/108937/TWCS
!
vlan 1782
 name 14/VLXX/108357/TWCS
!
vlan 1786
 name 14/KEFN/108623/TWCS
!
vlan 1787
 name 14/KEFN/108459/TWCS
!
vlan 1788
 name 14/KEFN/108280/TWCS
!
vlan 1795
 name 14/KEFN/108402/TWCS
!
vlan 1796
!
vlan 1801
 name 14/KFFN/108370/TWCS
!
vlan 1803
!
vlan 1808
 name 14/KEFN/108391/TWCS
!
vlan 1818
 name 14/KEFN/108365/TWCS
!
vlan 1822
 name 14/KFFN/108436/TWCS
!
vlan 1827
 name 14/KEFN/108431/TWCS
!
vlan 1837
 name 14/KEFN/108471/TWCS
!
vlan 1839
 name 14/KEFN/108488/TWCS
!
vlan 1842
 name 14/KFFN/108493/TWCS
!
vlan 1854
 name 14/KFFN/104332/TWCS
!
vlan 1859
 name 14/KEFN/108518/TWCS
!
vlan 1861,1867
!
vlan 1872
 name 14/KEFN/108604/TWCS
!
vlan 1876
!
vlan 1887
 name 14/VLXX/102296/TWCS
!
vlan 1894
 name 14/KEFN/108595/TWCS
!
vlan 1901
 name 14/KEFN/108510/TWCS
!
vlan 1908
 name 14/KEFN/108544/TWCS
!
vlan 1909
 name 14/KEFN/108540/TWCS
!
vlan 1916
 name 14/VLXX/101344/TWCS
!
vlan 1921
 name 14/VLXX/101170/TWCS
!
vlan 1924
 name 14/KEFN/108721/TWCS
!
vlan 1942
 name 14/KEFN/108926/TWCS
!
vlan 1943
 name 14/VLXX/100564/TWCS
!
vlan 1949
 name 14/VLXX/006571/TWCS
!
vlan 1959
 name 14/KEFN/108719/TWCS
!
vlan 1968
 name 14/KEFN/107934/TWCS
!
vlan 1995
 name VLAN_1995
!
vlan 1999
 name 14/KEFN/108884/TWCS
!
vlan 2004
 name 14/KEFN/108888/TWCS
!
vlan 2013
 name 14/KEFN/107126/TWCS
!
vlan 2014
 name 14/KEFN/108900/TWCS
!
vlan 2029
 name 14/VLXX/108633/TWCS
!
vlan 2030
 name 14/KEFN/108808/TWCS
!
vlan 2031
 name 14/KEFN/109072/TWCS
!
vlan 2042
!
vlan 2045
 name 14/KEFN/108864/TWCS
!
vlan 2046
 name 14/KEFN/108688/TWCS
!
vlan 2051
!
vlan 2052
 name 14/KDFN/108468/TWCS
!
vlan 2053
 name 14/KEFN/108749/TWCS
!
vlan 2054
 name 14/KEFN/108030/TWCS
!
vlan 2055
 name 14/KEFN/107139/TWCS
!
vlan 2056
 name 14/KEFN/108913/TWCS
!
vlan 2063
 name 14/KEFN/107116/TWCS
!
vlan 2066
 name 14/KFFN/104465/TWCS
!
vlan 2067
 name 14/KFFN/108792/TWCS
!
vlan 2068
 name 14/KFFN/108791/TWCS
!
vlan 2069
 name 14/KEFN/108869/TWCS
!
vlan 2070
 name 14/KEFN/108914/TWCS
!
vlan 2072
 name 14/KFFN/108918/TWCS
!
vlan 2073
 name 14/KEFN/108961/TWCS
!
vlan 2074
 name 14/KEFN/109039/TWCS
!
vlan 2080
 name 14/KEFN/108712/TWCS
!
vlan 2109
!
vlan 2112
 name 14/KEFN/108775/TWCS
!
vlan 2128
 name 14/KEFN/108790/TWCS
!
vlan 2306
 name 14/KEFN/108803/TWCS
!
vlan 2599
 name 14/KEFN/107130/TWCS
!
vlan 2704
 name 14/VLXX/102311/TWCS
!
vlan 2832
 name 14/VLXX/104494/TWCS
!
vlan 2891
 name 14/VLXX/101842/TWCS
!
vlan 2892
 name 14/VLXX/101816/TWCS
!
vlan 3251
!
vlan 3265
 name vlan3265
!
vlan 3273
 name 14/VLXX/108583/TWCS
!
vlan 3411
 name 14/VLXX/104038/TWCS
!
vlan 3444
 name 14/VLXX/100564/TWCS_1
!
vlan 3692
 name 14/KFFN/004898/TWCS/A
!
vlan 3712
!
vlan 3730
 name 14/KFFN/004544/TWCS
!
vlan 3807
 name 14/KEFN/107382/TWCS
!
vlan 3813
 name 14/KEFN/107989/TWCS
!
vlan 3816
 name 14/KEFN/108320/TWCS
!
vlan 3817
 name 14/KEFN/108324/TWCS
!
vlan 3819
 name 14/KEFN/108408/TWCS
!
vlan 3820
 name 14/KEFN/108411/TWCS
!
vlan 3999-4001,4007,4083-4094
!
ip ftp source-interface Vlan512
ip telnet source-interface Vlan512
ip tftp source-interface Vlan512
!
!
class-map match-any PR
  description classifies Priority Data
  match ip dscp cs2  af21  af22  af23
class-map match-any RT
  description classifies Real Time & VoIP
  match ip dscp cs5  ef
class-map match-any IA
  description classifies Interactive traffic
  match ip dscp cs4  af41  af42  af43
class-map match-any MC
  description classifies Mission Critical
  match ip dscp cs3  af31  af32  af33  cs6  cs7
class-map match-all RT+IA
  description classifies Real-Time and Interactive
  match ip dscp cs4  af41  af42  af43  cs5  ef
class-map match-all IP-ANY
  match access-group 3
class-map match-any TW-VOICE
  description classifies TW-VOICE (DSCP 47)
  match ip dscp 47
!
policy-map 30mb
 description 30Mb Rate Limit
  class IP-ANY
    police cir 34500000 bc 937500 conform-action set-dscp-transmit default exceed-action drop
policy-map 45mb
 description 45Mb Rate Limit
  class IP-ANY
    police cir 51750000 bc 1406250 conform-action set-dscp-transmit default exceed-action drop
policy-map 100mb
 description 100Mb Rate Limit
  class IP-ANY
    police cir 115000000 bc 3125000 conform-action set-dscp-transmit default exceed-action drop
policy-map BM
 description Broadcast Mitigation for Management
  class class-default
    police cir 5500000
policy-map OVERTURE
 description Policy for all Overture Ports
  class IP-ANY
   set ip dscp default
policy-map 55mb
 description 55Mb Rate Limit
  class IP-ANY
    police cir 63250000 bc 1718750 conform-action set-dscp-transmit default exceed-action drop
policy-map 4mb
 description 4Mb Rate Limit
  class IP-ANY
    police cir 4600000 bc 125000 conform-action set-dscp-transmit default exceed-action drop
policy-map 65mb
 description 65Mb Rate Limit
  class IP-ANY
    police cir 74750000 bc 2031250 conform-action set-dscp-transmit default exceed-action drop
policy-map 550mb
 description 550Mb Rate Limit
  class IP-ANY
    police cir 610496000 bc 17187500 conform-action set-dscp-transmit default exceed-action drop
policy-map 40mb
 description 40Mb Rate Limit
  class IP-ANY
    police cir 46000000 bc 1250000 conform-action set-dscp-transmit default exceed-action drop
policy-map 150mb
 description 150Mb Rate Limit
  class IP-ANY
    police cir 166500000 bc 4687500 conform-action set-dscp-transmit default exceed-action drop
policy-map 125mb
 description 125Mb Rate Limit
  class IP-ANY
    police cir 138750000 bc 3906250 conform-action set-dscp-transmit default exceed-action drop
policy-map 85mb
 description 85Mb Rate Limit
  class IP-ANY
    police cir 97750000 bc 2656250 conform-action set-dscp-transmit default exceed-action drop
policy-map INTEGRAL_ACCESS
 description Policy for all Integral Access Ports
  class IP-ANY
   set ip dscp default
policy-map 75mb
 description 75Mb Rate Limit
  class IP-ANY
    police cir 86250000 bc 2343750 conform-action set-dscp-transmit default exceed-action drop
policy-map 90mb
 description 90Mb Rate Limit
  class IP-ANY
    police cir 103500000 bc 2812500 conform-action set-dscp-transmit default exceed-action drop
policy-map 25mb
 description 25Mb Rate Limit
  class IP-ANY
    police cir 28750000 bc 781250 conform-action set-dscp-transmit default exceed-action drop
policy-map 750mb
 description 750Mb Rate Limit
  class IP-ANY
    police cir 832496000 bc 23437500 conform-action set-dscp-transmit default exceed-action drop
policy-map 800mb
 description 800Mb Rate Limit
  class IP-ANY
    police cir 888000000 bc 25000000 conform-action set-dscp-transmit default exceed-action drop
policy-map 80mb
 description 80Mb Rate Limit
  class IP-ANY
    police cir 92000000 bc 2500000 conform-action set-dscp-transmit default exceed-action drop
policy-map 50mb
 description 50Mb Rate Limit
  class IP-ANY
    police cir 57500000 bc 1562500 conform-action set-dscp-transmit default exceed-action drop
policy-map 300mb
 description 300Mb Rate Limit
  class IP-ANY
    police cir 333000000 bc 9375000 conform-action set-dscp-transmit default exceed-action drop
policy-map 650mb
 description 650Mb Rate Limit
  class IP-ANY
    police cir 721496000 bc 20312500 conform-action set-dscp-transmit default exceed-action drop
policy-map 350mb
 description 350Mb Rate Limit
  class IP-ANY
    police cir 388496000 bc 10937500 conform-action set-dscp-transmit default exceed-action drop
policy-map 6mb
 description 6Mb Rate Limit
  class IP-ANY
    police cir 6900000 bc 187500 conform-action set-dscp-transmit default exceed-action drop
policy-map INTERNAL_DSCP_SETTING
  class RT
   set dscp cs5
  class IA
   set dscp cs4
  class MC
   set dscp cs3
  class PR
   set dscp cs2
  class IP-ANY
   set dscp default
policy-map 60mb
 description 60Mb Rate Limit
  class IP-ANY
    police cir 69000000 bc 1875000 conform-action set-dscp-transmit default exceed-action drop
policy-map 225mb
 description 225Mb Rate Limit
  class IP-ANY
    police cir 249744000 bc 7031250 conform-action set-dscp-transmit default exceed-action drop
policy-map 70mb
 description 70Mb Rate Limit
  class IP-ANY
    police cir 80500000 bc 2187500 conform-action set-dscp-transmit default exceed-action drop
policy-map 500mb
 description 500Mb Rate Limit
  class IP-ANY
    police cir 555000000 bc 15625000 conform-action set-dscp-transmit default exceed-action drop
policy-map 10mb
 description 10Mb Rate Limit
  class IP-ANY
    police cir 11500000 bc 312500 conform-action set-dscp-transmit default exceed-action drop
policy-map 900mb
 description 900Mb Rate Limit
  class IP-ANY
    police cir 999000000 bc 28125000 conform-action set-dscp-transmit default exceed-action drop
policy-map 250mb
 description 250Mb Rate Limit
  class IP-ANY
    police cir 277496000 bc 7812500 conform-action set-dscp-transmit default exceed-action drop
policy-map 950mb
 description 950Mb Rate Limit
  class IP-ANY
    police cir 1054496000 bc 29687500 conform-action set-dscp-transmit default exceed-action drop
policy-map 200mb
 description 200Mb Rate Limit
  class IP-ANY
    police cir 222000000 bc 6250000 conform-action set-dscp-transmit default exceed-action drop
policy-map 2mb
 description 2Mb Rate Limit
  class IP-ANY
    police cir 2300000 bc 62500 conform-action set-dscp-transmit default exceed-action drop
policy-map 700mb
 description 700Mb Rate Limit
  class IP-ANY
    police cir 777000000 bc 21875000 conform-action set-dscp-transmit default exceed-action drop
policy-map 850mb
 description 850Mb Rate Limit
  class IP-ANY
    police cir 943496000 bc 26562500 conform-action set-dscp-transmit default exceed-action drop
policy-map 95mb
 description 95Mb Rate Limit
  class IP-ANY
    police cir 109250000 bc 2968750 conform-action set-dscp-transmit default exceed-action drop
policy-map 20mb
 description 20Mb Rate Limit
  class IP-ANY
    police cir 23000000 bc 625000 conform-action set-dscp-transmit default exceed-action drop
policy-map 15mb
 description 15Mb Rate Limit
  class IP-ANY
    police cir 17250000 bc 468750 conform-action set-dscp-transmit default exceed-action drop
policy-map 175mb
 description 175Mb Rate Limit
  class IP-ANY
    police cir 194250000 bc 5468750 conform-action set-dscp-transmit default exceed-action drop
policy-map 600mb
 description 600Mb Rate Limit
  class IP-ANY
    police cir 666000000 bc 18750000 conform-action set-dscp-transmit default exceed-action drop
policy-map 1000mb
 description 1000Mb (1Gig) Rate Limit
  class IP-ANY
    police cir 1110000000 bc 31250000 conform-action set-dscp-transmit default exceed-action drop
policy-map NLAN
 description Policy for Best Effort SNLAN ports
  class IP-ANY
   set ip dscp default
policy-map 35mb
 description 35Mb Rate Limit
  class IP-ANY
    police cir 40250000 bc 1093750 conform-action set-dscp-transmit default exceed-action drop
policy-map 450mb
 description 450Mb Rate Limit
  class IP-ANY
    police cir 499496000 bc 14062500 conform-action set-dscp-transmit default exceed-action drop
policy-map 8mb
 description 8Mb Rate Limit
  class IP-ANY
    police cir 9200000 bc 250000 conform-action set-dscp-transmit default exceed-action drop
policy-map 400mb
 description 400Mb Rate Limit
  class IP-ANY
    police cir 444000000 bc 12500000 conform-action set-dscp-transmit default exceed-action drop
!
!
!
!
!
interface Port-channel41
 description 102/GE1L/IPLTINSD/IPLTINSD - IPLTINSDI6002: ag-1 [OVT]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 172,406,448,505,506,651,952,1208,1450,1471-1473,1481,1546,1562,1563,1578,1609,1623,1625,1643,1661,1662,1673,1679,1681-1683,1743,1748,1942,2014,2046,2832,3010,3265,3273,4007
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 mls qos trust cos
 l2protocol-tunnel drop-threshold cdp 1000
 l2protocol-tunnel drop-threshold stp 1000
 l2protocol-tunnel drop-threshold vtp 1000
 l2protocol-tunnel cdp
 l2protocol-tunnel stp
 l2protocol-tunnel vtp
 no keepalive
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
!
interface Port-channel51
 description 116/GE1L/IPLTINSD/IPLTINSD - IPLTINSD9K001/IPLTINSD9K002: Be51 [METRO]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 mls qos trust cos
 spanning-tree portfast trunk
!
interface Port-channel70
 no ip address
 shutdown
!
interface Port-channel71
 no ip address
 shutdown
!
interface GigabitEthernet1/1
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/2
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/3
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/4
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/5
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/6
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/7
 description N14573 [METRO - Ring 3.1]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 319,461,501,502,505,506,607,656,828,845,1195,1201,1290,1330,1393,1458,1481,1497,1520,1544,1742,1872,1876,1916,1995,2031,2074,3692,3813,4001,4094
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 94 edge primary
 rep preempt delay 15
 rep block port 16 vlan 1-4094
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/8
 description N14578 [METRO - Ring 3.2]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 41,290,501,502,505,506,574,616,699,1168,1233,1236,1241,1247,1249,1252,1358,1366,1372,1425,1434,1471,1480,1481,1485,1499,1505,1551,1610,1614,1645,1695,1704,1740,1782,1916,2013,2063,2599,3816,4001,4093
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 93 edge primary
 rep preempt delay 15
 rep block port 28 vlan 1-4094
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/9
 description N14408 [METRO - Ring 3.4]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 167,168,404,411,493,502,505,506,522,543,661,1060,1080,1227,1296,1316,1328,1357,1370,1671,1887,1995,2069,3251,3692,4001,4091
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/10
 switchport
 switchport access vlan 4000
 switchport mode access
 switchport nonegotiate
 mtu 9216
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
 no keepalive
 no cdp enable
!
interface GigabitEthernet1/11
 description N14596 [METRO - Ring 3.6]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 284,285,339,502,505,506,512,749,758,1243,1367,1584,1722,1756,2055,4001,4089
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/12
 switchport
 switchport access vlan 4000
 switchport mode access
 switchport nonegotiate
 mtu 9216
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
 no cdp enable
 spanning-tree portfast
 spanning-tree bpdufilter enable
!
interface GigabitEthernet1/13
 description N14608 [METRO - Ring 3.10]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 501,502,506,1573,1693,1694,1696,2054,4001,4085
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 85 edge primary
 rep preempt delay 15
 rep block port 6 vlan 1-4094
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/14
 description N14611 [METRO - Ring 3.11]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 502,506,1731,1968,4001,4084
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 84 edge primary
 rep preempt delay 15
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/15
 description 14/KFFN/108262/TWCS - QWEST COMMUNICATIONS # 200222 [UNI]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 505,506,1778
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 bandwidth 1000000
 speed nonegotiate
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 flowcontrol send off
 no keepalive
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
!
interface GigabitEthernet1/16
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/17
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/18
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/19
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/20
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/21
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/22
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/23
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/24
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/25
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/26
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/27
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/28
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/29
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/30
 description N14466 [METRO - Ring 3.9]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 625,1754,1755,2072,2745,4001,4086
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet1/31
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/32
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/33
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/34
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/35
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/36
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/37
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/38
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/39
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/40
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/41
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/42
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/43
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/44
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/45
 description 107/GE1/IPLTINSD/IPLTINSD - IPLTINSDI6002: gigabit 0.1 [OVT]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 172,406,448,505,506,651,952,1208,1450,1471-1473,1481,1546,1562,1563,1578,1609,1623,1625,1643,1661,1662,1673,1679,1681-1683,1743,1748,1942,2014,2046,2832,3010,3265,3273,4007
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
 l2protocol-tunnel drop-threshold cdp 1000
 l2protocol-tunnel drop-threshold stp 1000
 l2protocol-tunnel drop-threshold vtp 1000
 l2protocol-tunnel cdp
 l2protocol-tunnel stp
 l2protocol-tunnel vtp
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
 channel-group 41 mode on
!
interface GigabitEthernet1/46
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/47
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet1/48
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface TenGigabitEthernet3/1
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 22,27,49,56,65,83-85,99,129,142,288,306,319,321,403,411,448,461,474,512,518,551,552,563,571,578,589,596,607,616,625,631,637,645,651,656,661,669,672,673,677,678,688,694,699,730,749,810,814,839,845,857,863,885,952,953,969,1054,1079,1091,1114,1167,1178,1195,1200,1201,1204,1236,1249,1338,1425,1433,1437,1485,1492,1511,1512,1544,1573,1578,1584,1609,1610,1624-1626,1643,1651,1661,1662,1671,1693-1696,1716,1722,1731,1738,1742,1743,1753-1756,1762,1764,1767-1769,1786,1788,1795,1822,1837,1854,1872,1894,1901,1908,1924,1959,1968,2052,2054,2055,2063,2704,2891,2892,3712,3730,3807,3816,3817,3819,3820
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 load-interval 30
 shutdown
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 mls qos vlan-based
 mls qos trust cos
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
!
interface TenGigabitEthernet3/2
 description N14581 [METRO - Ring 3.3]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 16,35,38,44,47-51,122,236,304,399,471,474,501,502,505,506,664,672,678,694,804,814,1200,1243,1361,1446,1462,1482,1506,1531,1570,1571,1617,1640,1716,1721,1738,1762,1764,1780,1781,1803,1827,1859,1887,1894,1908,1999,2051,2053,2056,2067,2068,2073,2109,2112,2306,3411,3999,4001,4092
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 92 edge primary
 rep block port 2 vlan 1-4094
 mls qos trust cos
!
interface TenGigabitEthernet3/3
 description 101/GE10/IPLTINSD/IPLTINSD - IPLTINSD9K001: Te0/0/0/23 [METRO]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0
 rcv-queue cos-map 1 3 1 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 channel-group 51 mode active
!
interface TenGigabitEthernet3/4
 description N14588 [METRO - Ring 3.5]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 30,128,501,502,505,506,1421,4001,4090
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 90 edge primary
 rep block port 2 vlan 1-4094
 mls qos trust cos
!
interface TenGigabitEthernet3/5
 description N14599 [METRO - Ring 3.8]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 36,46,65,67,106,384,413,501,502,505,506,518,544,559,571,578,608,631,700,818,839,867,1142,1190,1202,1253,1254,1269,1500,1624,1724,1753,1796,1801,1808,1811,1822,1842,1854,1909,1921,1959,1996,2029,2042,2045,2066,2080,2128,3273,3692,3819,4001,4087
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 87 edge primary
 mls qos trust cos
!
interface TenGigabitEthernet3/6
 description N14600 [METRO - Ring 3.7]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 40,42,70,115,184,490,501,502,505,506,645,669,687,953,995,1114,1178,1338,1446,1535,1559,1641,1642,1647,1665,1690,1730,1750,1766-1769,1787,1788,1818,1837,1839,1867,1901,1916,1943,1995,2004,2030,2052,2070,3444,3692,3820,4001,4088
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 88 edge primary
 mls qos trust cos
!
interface TenGigabitEthernet3/7
 description N14615 [METRO]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 501,502,506,1433,1795,1949,4001,4083
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 83 edge primary
 mls qos trust cos
!
interface TenGigabitEthernet3/8
 switchport
 switchport access vlan 4000
 switchport mode dot1q-tunnel
 switchport nonegotiate
 mtu 9216
 shutdown
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 no keepalive
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
!
interface GigabitEthernet5/1
 switchport
 switchport trunk encapsulation dot1q
 switchport mode trunk
 switchport nonegotiate
 speed nonegotiate
 l2protocol-tunnel shutdown-threshold cdp 200
 l2protocol-tunnel shutdown-threshold stp 200
 l2protocol-tunnel shutdown-threshold vtp 200
 l2protocol-tunnel cdp
 l2protocol-tunnel stp
 l2protocol-tunnel vtp
 no keepalive
 no cdp enable
 spanning-tree portfast
 spanning-tree bpdufilter enable
!
interface GigabitEthernet5/2
 description 14/KFFN/108452/TWCS - IPLTINSDTS007: Elect/Test [TSET - BV-10 - 10.240.81.6]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 1716,1861
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 media-type rj45
 speed 1000
 duplex full
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
 spanning-tree portfast
 spanning-tree bpdufilter enable
!
interface GigabitEthernet6/1
 no ip address
 shutdown
!
interface GigabitEthernet6/2
 no ip address
 shutdown
!
interface TenGigabitEthernet7/1
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan none
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 load-interval 30
 shutdown
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 mls qos vlan-based
 mls qos trust cos
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
!
interface TenGigabitEthernet7/2
 description N14581 [METRO - Ring 3.3]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 16,35,38,44,47-51,122,236,304,399,471,474,501,502,505,506,664,672,678,694,804,814,1200,1243,1361,1446,1462,1482,1506,1531,1570,1571,1617,1640,1716,1721,1738,1762,1764,1780,1781,1803,1827,1859,1887,1894,1908,1999,2051,2053,2056,2067,2068,2073,2109,2112,2306,3411,3999,4001,4092
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 92 edge
 mls qos trust cos
!
interface TenGigabitEthernet7/3
 description 102/GE10/IPLTINSD/IPLTINSD - IPLTINSD9K002: Te0/0/0/23 [METRO - NOMON]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0
 rcv-queue cos-map 1 3 1 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 channel-group 51 mode active
!
interface TenGigabitEthernet7/4
 description N14588 [METRO - Ring 3.5]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 30,128,501,502,505,506,1421,4001,4090
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 90 edge
 mls qos trust cos
!
interface TenGigabitEthernet7/5
 description N14599 [METRO - Ring 3.8]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 36,46,65,67,106,384,413,501,502,505,506,518,544,559,571,578,608,631,700,818,839,867,1142,1190,1202,1253,1254,1269,1500,1624,1724,1753,1796,1801,1808,1811,1822,1842,1854,1909,1921,1959,1996,2029,2042,2045,2066,2080,2128,3273,3692,3819,4001,4087
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 87 edge
 mls qos trust cos
!
interface TenGigabitEthernet7/6
 description N14600 [METRO - Ring 3.7]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 40,42,70,115,184,490,501,502,505,506,645,669,687,953,995,1114,1178,1338,1446,1535,1559,1641,1642,1647,1665,1690,1730,1750,1766-1769,1787,1788,1818,1837,1839,1867,1901,1916,1943,1995,2004,2030,2052,2070,3444,3692,3820,4001,4088
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 88 edge
 mls qos trust cos
!
interface TenGigabitEthernet7/7
 description N14615 [METRO]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 501,502,506,1433,1795,1949,4000,4001,4083
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 rep segment 83 edge
 mls qos trust cos
!
interface TenGigabitEthernet7/8
 description 14/KGFN/106286/TWCS  - IPLTINSDEF001: T9/1 [EXFO 10.240.81.2]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan none
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 wrr-queue bandwidth 20 30 50 5 5 5 5
 wrr-queue random-detect min-threshold 1 50 50 50 50 60 60 60 60
 wrr-queue random-detect min-threshold 2 60 60 60 60 70 70 70 70
 wrr-queue random-detect min-threshold 3 70 70 70 70 100 100 100 100
 wrr-queue random-detect max-threshold 1 80 80 80 80 90 90 90 90
 wrr-queue random-detect max-threshold 2 90 90 90 90 100 100 100 100
 wrr-queue random-detect max-threshold 3 80 80 80 80 100 100 100 100
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0
 rcv-queue cos-map 1 3 1 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 spanning-tree portfast
 spanning-tree bpdufilter enable
!
interface GigabitEthernet9/1
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/2
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/3
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/4
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/5
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/6
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/7
 description N14573 [METRO - Ring 3.1]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 319,461,501,502,505,506,607,656,828,845,1195,1201,1290,1330,1393,1458,1481,1497,1520,1544,1742,1872,1876,1916,1995,2031,2074,3692,3813,4001,4094
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 94 edge
 rep preempt delay 15
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/8
 description N14578 [METRO - Ring 3.2]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 41,290,501,502,505,506,574,616,699,1168,1233,1236,1241,1247,1249,1252,1358,1366,1372,1425,1434,1471,1480,1481,1485,1499,1505,1551,1610,1614,1645,1695,1704,1740,1782,1916,2013,2063,2599,3816,4001,4093
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 93 edge
 rep preempt delay 15
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/9
 description N14408 [METRO - Ring 3.4]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 167,168,404,411,493,502,505,506,522,543,661,1060,1080,1227,1296,1316,1328,1357,1370,1671,1887,1995,2069,3251,3692,4001,4091
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/10
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/11
 description N14596 [METRO - Ring 3.6]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 284,285,339,502,505,506,512,749,758,1243,1367,1584,1722,1756,2055,4001,4089
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/12
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/13
 description N14608 [METRO - Ring 3.10]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 501,502,506,1573,1693,1694,1696,2054,4001,4085
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 85 edge
 rep preempt delay 15
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/14
 description N14611 [METRO - Ring 3.11]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 502,506,1731,1968,4001,4084
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 rep segment 84 edge
 rep preempt delay 15
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/15
 description 14/KFFN/108300/TWCS - QWEST COMMUNICATIONS # 200222 [UNI]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 505,506,1779
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 bandwidth 300000
 speed nonegotiate
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 mls qos trust cos
 flowcontrol send off
 no keepalive
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
!
interface GigabitEthernet9/16
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/17
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/18
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/19
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/20
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/21
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/22
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/23
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/24
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/25
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/26
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/27
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/28
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/29
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/30
 description N14466 [METRO - Ring 3.9]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk native vlan 4001
 switchport trunk allowed vlan 625,1754,1755,2072,2745,4001,4086
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 udld port aggressive
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
!
interface GigabitEthernet9/31
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/32
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/33
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/34
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/35
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/36
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/37
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/38
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/39
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/40
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/41
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/42
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/43
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/44
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/45
 description 107/GE1/IPLTINSD/IPLTINSD - IPLTINSDI6002: gigabit 0.2 [OVT]
 switchport
 switchport trunk encapsulation dot1q
 switchport trunk allowed vlan 172,406,448,505,506,651,952,1208,1450,1471-1473,1481,1546,1562,1563,1578,1609,1623,1625,1643,1661,1662,1673,1679,1681-1683,1743,1748,1942,2014,2046,2832,3010,3265,3273,4007
 switchport mode trunk
 switchport nonegotiate
 mtu 9216
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 mls qos trust cos
 flowcontrol send off
 l2protocol-tunnel drop-threshold cdp 1000
 l2protocol-tunnel drop-threshold stp 1000
 l2protocol-tunnel drop-threshold vtp 1000
 l2protocol-tunnel cdp
 l2protocol-tunnel stp
 l2protocol-tunnel vtp
 no cdp enable
 spanning-tree portfast trunk
 spanning-tree bpdufilter enable
 channel-group 41 mode on
!
interface GigabitEthernet9/46
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/47
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 1 2 2
 wrr-queue cos-map 2 1 3
 wrr-queue cos-map 2 2 6 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 2 2
 rcv-queue cos-map 1 3 3
 rcv-queue cos-map 1 4 6 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface GigabitEthernet9/48
 mtu 9216
 no ip address
 shutdown
 wrr-queue bandwidth 20 30 50
 wrr-queue cos-map 1 1 0 1
 wrr-queue cos-map 3 1 3
 wrr-queue cos-map 3 6 6
 wrr-queue cos-map 3 7 7
 priority-queue cos-map 1 4 5
 rcv-queue cos-map 1 1 0 1
 rcv-queue cos-map 1 3 2
 rcv-queue cos-map 1 4 3
 rcv-queue cos-map 1 6 6
 rcv-queue cos-map 1 7 7
 rcv-queue cos-map 1 8 4 5
 no snmp trap link-status
 flowcontrol send off
!
interface Vlan1
 no ip address
!
interface Vlan501
 no ip address
 no ip redirects
 shutdown
 service-policy input BM
 service-policy output BM
!
interface Vlan502
 no ip address
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan505
 ip address 10.253.151.1 255.255.255.0
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan506
 no ip address
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan512
 description Network Management Interface (IDN) Port
 ip address 10.249.144.6 255.255.255.224
 no ip redirects
 no ip proxy-arp
 service-policy input BM
 service-policy output BM
!
interface Vlan4000
 no ip address
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4001
 no ip address
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4007
 ip address 10.253.149.1 255.255.255.0
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4083
 ip address 10.253.145.129 255.255.255.224
 no ip redirects
!
interface Vlan4084
 ip address 10.253.145.97 255.255.255.224
 no ip redirects
!
interface Vlan4085
 ip address 10.253.145.65 255.255.255.224
 no ip redirects
!
interface Vlan4086
 ip address 10.253.145.33 255.255.255.224
 no ip redirects
!
interface Vlan4087
 ip address 10.253.145.1 255.255.255.224
 no ip redirects
!
interface Vlan4088
 ip address 10.253.144.225 255.255.255.224
 no ip redirects
!
interface Vlan4089
 ip address 10.253.144.193 255.255.255.224
 no ip redirects
!
interface Vlan4090
 ip address 10.253.144.161 255.255.255.224
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4091
 ip address 10.253.144.129 255.255.255.224
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4092
 ip address 10.253.144.97 255.255.255.224
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4093
 ip address 10.253.144.65 255.255.255.224
 no ip redirects
 service-policy input BM
 service-policy output BM
!
interface Vlan4094
 ip address 10.253.144.33 255.255.255.224
 no ip redirects
 service-policy input BM
 service-policy output BM
!
ip classless
ip route 0.0.0.0 0.0.0.0 10.249.144.1
!
no ip http server
no ip http secure-server
ip tacacs source-interface Vlan512
!
logging history informational
logging facility local0
logging source-interface Vlan512
logging 10.1.22.122
logging 66.162.108.21
logging 50.58.29.21
access-list 1 permit 50.58.29.21
access-list 1 permit 66.162.108.21
access-list 2 remark SNMP-verisimilitude
access-list 2 permit 10.1.22.122
access-list 2 permit 207.67.69.0 0.0.0.255
access-list 2 permit 168.215.252.0 0.0.0.255
access-list 2 permit 66.195.92.0 0.0.0.255
access-list 2 permit 50.59.86.0 0.0.0.255
access-list 3 permit any
access-list 4 remark SNMP-turpitude
access-list 4 permit 10.1.22.122
access-list 4 permit 207.67.69.0 0.0.0.255
access-list 4 permit 168.215.252.0 0.0.0.255
access-list 4 permit 66.195.92.0 0.0.0.255
access-list 4 permit 50.59.86.0 0.0.0.255
access-list 5 remark SNMP-twtinet94
access-list 5 permit 10.1.22.122
access-list 5 permit 207.67.69.0 0.0.0.255
access-list 5 permit 168.215.252.0 0.0.0.255
access-list 5 permit 66.195.92.0 0.0.0.255
access-list 5 permit 50.59.86.0 0.0.0.255
access-list 50 permit 10.253.144.0 0.0.7.255
access-list 198 permit tcp any any established
access-list 198 deny   ip any any log
snmp-server community verisimilitude RO 2
snmp-server community turpitude RO 4
snmp-server community twtinet94 RO 5
snmp-server trap-source Vlan512
snmp-server trap timeout 1
snmp-server queue-length 500
snmp-server location 1465 Gent Avenue, Indianapolis, IN
snmp-server enable traps snmp authentication linkdown linkup coldstart warmstart
snmp-server enable traps chassis
snmp-server enable traps module
snmp-server enable traps config
snmp-server enable traps rf
snmp-server enable traps rtr
snmp-server enable traps bridge newroot topologychange
snmp-server enable traps fru-ctrl
snmp-server enable traps entity
snmp-server enable traps vlancreate
snmp-server enable traps vlandelete
snmp-server enable traps envmon fan shutdown supply temperature status
snmp-server host 10.1.22.209 version 2c turpitude
snmp-server host 10.1.22.210 version 2c turpitude
snmp-server host 10.1.22.62 version 2c verisimilitude
snmp ifmib ifalias long
!
tacacs-server host 66.162.108.21 port 1060
tacacs-server host 50.58.29.21 port 1060
tacacs-server key 7 06570534115B385113223D0319370B333106367335173857152D7861005D5A234321370268587376705B450725280A011E0B5B0B6117163E08110437501D5A553B27263770260A32070F09150F4E47421A
!
control-plane
!
banner login ^C "\r\n\r\n                            IPLTINSDC6003\r\n\r\n                       Authorized access only.\r\n\r\n       \tThis system is the property of tw telecom Inc.\r\n      Disconnect IMMEDIATELY if you are not an authorized user.\r\n                 For help, contact dsg@twtelecom.com\r\n\r\n" ^C
!
line con 0
 login authentication conslogin
line vty 0 4
 login authentication vtylogin
 transport input telnet
line vty 5 15
 login authentication vtylogin
 transport input telnet
!
ntp clock-period 17180114
ntp source Vlan512
ntp access-group peer 1
ntp access-group serve 50
ntp update-calendar
ntp server 50.58.29.21
ntp server 66.162.108.21
!

end

EOF
