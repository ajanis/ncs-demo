# NSO Demonstration

## NSO Installation
Download and install Cisco NSO and Cisco NEDs following Cisco Documentation for your local machine OS:

[https://developer.cisco.com/docs/nso/#!getting-and-installing-nso/local-vs-system-installation](https://developer.cisco.com/docs/nso/#!getting-and-installing-nso/local-vs-system-installation)

### Quick Install Reference

The directories and paths used in this reference are just examples.
You can use any directory names and installation paths that you wish.

* Create project directories, set up Python venv, local-install NSO, create NCS project, add NED packages, set up git repository
  - Download files from Cisco
  - Create directory structure
  - Move install packages into ~/nso/ncs-571-pkgs directory
  - Python 2.7.9 required, create python virtual environment,pyenv,etc.
  - Create NSO local installation in ~nso/nso-installation
  - Source ncsrc file (add to your shell profile)
  - Create NCS project in ~/nso/ncs-project
  - Extract NED packages into ~/nso/ncs-project/packages/
  - Create project metadata file
  - Initialize git repository and add .ignores and project files (optional for demo, strongly recommended for production)


### Creating a Local-Install Project with Version Control

* Local-Install NSO
```bash
❯ mkdir -p ~/nso/{ncs-571-pkgs,nso-installation}
❯ mv *.bin ~/nso/ncs-571-pkgs/
❯ cd ~/nso/ncs-571-pkgs
❯ echo '2.7.9' > .python-version
❯ sh nso-5.7.1.darwin.x86_64.signed.bin
❯ ./nso-5.7.1.darwin.x86_64.installer.bin --local-install ~/nso/nso-installation
```
* Copy updated NEDs to NSO ```packages``` directory (can also be copied into the NCS project's packages directory)
```bash
❯ tar -C ~/nso/nso-installation/packages/neds -xvzf ~/nso/nso-571-pkgs/ncs-5.7-cisco-ios-6.77.10.tar.gz
❯ tar -C ~/nso/nso-installation/packages/neds -xvzf ~/nso/nso-571-pkgs/ncs-5.7-cisco-iosxr-cli-7.38.tar.gz
❯ tar -C ~/nso/nso-installation/packages/neds -xvzf ~/nso/nso-571-pkgs/ncs-5.7-cisco-nx-cli-5.220.tar.gz
```
* Create NCS Project
```bash
❯ cd ~/nso/nso-installation
❯ source ncsrc
❯ ncs-project create ncs-project
❯ cd ncs-project
❯ echo '2.7.9' > .python-version
```
* Copy desired neds from nso-installation/packages/neds into ncs-project
```bash
❯ cp -a ~/nso/nso-installation/packages/neds/* ~nso/nso-installation/ncs-project/packages/
```
* Modify ```project-meta-data.xml``` File for Quick Device Setup
```xml
<project-meta-data xmlns="http://tail-f.com/ns/ncs-project">
  <name>demo-project</name>
  <project-version>1.0</project-version>
  <description>Skeleton for a NCS project</description>

  <netsim>
    <device>
      <name>cisco-ios-cli-6.77</name>
      <prefix>cisco-ios-</prefix>
      <num-devices>4</num-devices>
    </device>
  </netsim>

  <package>
    <name>cisco-ios-cli-6.77</name>
    <local/>
  </package>

  <package>
    <name>cisco-iosxr-cli-7.38</name>
    <local/>
  </package>

  <package>
    <name>cisco-nx-cli-5.22</name>
    <local/>
  </package>

</project-meta-data>
```

* Initialize Git Repo for Version Control
* Create ```.gitignore``` files
* Add and Commit all files

```bash
❯ git init .
❯ echo -e '*\n!.gitignore' | tee ncs-cdb/.gitignore state/.gitignore logs/.gitignore; echo 'setup.mk*' | tee .gitignore
❯ git add .
❯ git commit -a -m "Initial Commit"
```

### Build and start netsim project (network, devices)
* Update netsim project and rebuild netsim
```bash
❯ ncs-project update -y
❯ make all start
```

```bash
# example output <truncated>
DEVICE cisco-ios-0 CREATED
DEVICE cisco-ios-1 CREATED
if [ ! -d ncs-cdb ]; then mkdir ncs-cdb; fi
if [ ! -d state ]; then mkdir state; fi
if [ ! -d logs ]; then mkdir logs; fi
if [ ! -d init_data ]; then mkdir init_data; fi
cp init_data/* ncs-cdb/. > /dev/null 2>&1 || true
[ -d netsim ] && ncs-netsim stop || true
DEVICE cisco-ios-0 already STOPPED
DEVICE cisco-ios-1 already STOPPED
ncs --stop || true
connection refused (stop)
ncs-netsim start
DEVICE cisco-ios-0 OK STARTED
DEVICE cisco-ios-1 OK STARTED
ncs
```

### Connecting to Netsim and Devices, Add/Change Device Configs, Service Templates/Models, Basic Usage Demo
* Connect to NCS CLI
```bash
ncs_cli -u admin -C

admin connected from 127.0.0.1 using console on C02ZG1J4LVDL
admin@ncs# packages reload               
reload-result {
    package cisco-ios-cli-6.77
    result true
}
reload-result {
    package cisco-iosxr-cli-7.38
    result true
}
reload-result {
    package cisco-nx-cli-5.22
    result true
}
admin@ncs# 
System message at 2022-12-01 03:56:54...
    Subsystem stopped: ncs-dp-1-cisco-ios-cli-6.77:IOSDp
admin@ncs# 
System message at 2022-12-01 03:56:54...
    Subsystem started: ncs-dp-2-cisco-ios-cli-6.77:IOSDp
admin@ncs# config
Entering configuration mode terminal
admin@ncs(config)# load merge ncs-cdb/netsim_devices_init.xml        
Loading.
2.85 KiB parsed in 0.00 sec (452.88 KiB/sec)
admin@ncs(config)#
admin@ncs(config)# commit
% No modifications to commit.
admin@ncs# show devices list
NAME         ADDRESS    DESCRIPTION  NED ID              ADMIN STATE  
--------------------------------------------------------------------
cisco-ios-0  127.0.0.1  -            cisco-ios-cli-6.77  unlocked     
cisco-ios-1  127.0.0.1  -            cisco-ios-cli-6.77  unlocked

admin@ncs# devices sync-from 
sync-result {
    device cisco-ios-0
    result true
}
sync-result {
    device cisco-ios-1
    result true
}
# example output <truncated>
admin@ncs# exit
```

* Create base configuration file at ```device-config```
```bash
policer aggregate DemoUser-pol cir 2000000 bc 375000 conform-action transmit exceed-action drop
policy-map DemoUser
 class class-default
  police aggregate DemoUser-pol
 !
!
vlan internal allocation policy ascending
vlan dot1q tag native
vlan 500
 name DemoUser
!
interface GigabitEthernet0/1
 port-type nni
 no shutdown
 udld port aggressive
 switchport
 switchport mode trunk
 switchport trunk allowed vlan 1,500,1000
exit
interface GigabitEthernet0/10
 description DemoUser
 media-type  rj45
 no mdix auto
 speed       100
 duplex      full
 no snmp trap link-status
 service-policy input DemoUser
 switchport
 switchport access vlan 500
 storm-control broadcast level 1.0
 no ethernet cfm interface
exit
```

* Load Configuration onto ```cisco-ios-0``` and ```cisco-ios-1```
```bash
❯ ncs-netsim cli-c cisco-ios-0

User admin last logged in 2022-12-01T11:00:59.844148+00:00, to C02ZG1J4LVDL, from 127.0.0.1 using cli-ssh
admin connected from 127.0.0.1 using console on C02ZG1J4LVDL
cisco-ios-0# config
Entering configuration mode terminal
cisco-ios-0(config)# load merge ../../../device-config
Loading.
714 bytes parsed in 0.10 sec (6.52 KiB/sec)
cisco-ios-0(config)# commit
cisco-ios-0(config)# exit
cisco-ios-0# exit

❯ ncs-netsim cli-c cisco-ios-1

User admin last logged in 2022-12-01T11:00:59.844148+00:00, to C02ZG1J4LVDL, from 127.0.0.1 using cli-ssh
admin connected from 127.0.0.1 using console on C02ZG1J4LVDL
cisco-ios-0# config
Entering configuration mode terminal
cisco-ios-1(config)# load merge ../../../device-config
Loading.
714 bytes parsed in 0.10 sec (6.52 KiB/sec)
cisco-ios-0(config)# commit
cisco-ios-0(config)# exit
cisco-ios-0# exit
```
* Sync NCS Database from ```cisco-ios-0``` and ```cisco-ios-1```
* Show ```cisco-ios-0``` and ```cisco-ios-1``` Config
```bash
❯ ncs_cli -u admin -C

User admin last logged in 2022-12-01T10:55:31.046055+00:00, to C02ZG1J4LVDL, from 127.0.0.1 using cli-console
admin connected from 127.0.0.1 using console on C02ZG1J4LVDL

admin@ncs# devices device cisco-ios-0 sync-from 
result true

admin@ncs# show running-config devices device cisco-ios-0

devices device cisco-ios-0
 address   127.0.0.1
 port      10022
 ssh host-key ssh-rsa
  key-data "AAAAB3NzaC1yc2EAAAADAQABAAABgQC5uEn575RJZ/CQUT6p93BSUXggctofWKCtghwWXvi..."
# example output <truncated>
  interface GigabitEthernet0/1
   port-type nni
   switchport
   switchport mode trunk
   switchport trunk allowed vlan 1,500,1000
   no shutdown
   udld port aggressive
  exit
  interface GigabitEthernet0/10
   description DemoUser
   switchport
   switchport access vlan 500
   media-type  rj45
   no mdix auto
   no ethernet cfm interface
   speed       100
   duplex      full
   no snmp trap link-status
   no ip address
   service-policy input DemoUser
   no shutdown
   storm-control broadcast level 1.0
  exit

 !
!
admin@ncs# show running-config devices device cisco-ios-1
# example output <truncated>
```
* Export Configuration as XML to use as a service template
```bash
admin@ncs# show running-config devices device cisco-ios-0 config | display xml | save cisco-ios-0-config.xml
```
*  Remove extra/unneeded configurations from template
```xml
<config xmlns="http://tail-f.com/ns/config/1.0">
  <devices xmlns="http://tail-f.com/ns/ncs">
    <device>
      <name>cisco-ios-0</name>
      <config>
        <policer xmlns="urn:ios">
          <aggregate>
            <name>DemoUser-pol</name>
            <cir>2000000</cir>
            <bc>375000</bc>
            <conform-action/>
            <transmit/>
            <exceed-action/>
            <drop/>
          </aggregate>
        </policer>
        <policy-map xmlns="urn:ios">
          <name>DemoUser</name>
          <class-default>
            <class>
              <name>class-default</name>
              <police-aggregate>
                <police>
                  <aggregate>DemoUser-pol</aggregate>
                </police>
              </police-aggregate>
            </class>
          </class-default>
        </policy-map>
        <vlan xmlns="urn:ios">
          <internal>
            <allocation>
              <policy>ascending</policy>
            </allocation>
          </internal>
          <dot1q>
            <tag>
              <native/>
            </tag>
          </dot1q>
          <vlan-list>
            <id>500</id>
            <name>DemoUser</name>
          </vlan-list>
        </vlan>
        <interface xmlns="urn:ios">
          <GigabitEthernet>
            <name>0/1</name>
            <port-type>nni</port-type>
            <switchport>
              <mode>
                <trunk/>
              </mode>
              <trunk>
                <allowed>
                  <vlan>
                    <vlans>1</vlans>
                    <vlans>500</vlans>
                    <vlans>1000</vlans>
                  </vlan>
                </allowed>
              </trunk>
            </switchport>
            <udld>
              <port>
                <aggressive/>
              </port>
            </udld>
          </GigabitEthernet>
          <GigabitEthernet>
            <name>0/10</name>
            <description>DemoUser</description>
            <switchport>
              <access>
                <vlan>500</vlan>
              </access>
            </switchport>
            <media-type>rj45</media-type>
            <mdix>
              <auto>false</auto>
            </mdix>
            <ethernet>
              <cfm>
                <interface>false</interface>
              </cfm>
            </ethernet>
            <speed>100</speed>
            <duplex>full</duplex>
            <snmp>
              <trap>
                <link-status>false</link-status>
              </trap>
            </snmp>
            <ip>
              <no-address>
                <address>false</address>
              </no-address>
            </ip>
            <service-policy>
              <input>DemoUser</input>
            </service-policy>
            <storm-control>
              <broadcast>
                <level>1.0</level>
              </broadcast>
            </storm-control>
          </GigabitEthernet>
        </interface>
      </config>
    </device>
  </devices>
</config>
```
* Create Service Package
```bash
❯ cd packages 
❯ ncs-make-package --service-skeleton template access
❯ ls
access             cisco-ios-cli-6.77

❯ find access/
access
access/package-meta-data.xml
access/test
access/test/Makefile
access/test/internal
access/test/internal/Makefile
access/test/internal/lux
access/test/internal/lux/Makefile
access/test/internal/lux/basic
access/test/internal/lux/basic/run.lux
access/test/internal/lux/basic/Makefile
access/templates
access/templates/access-template.xml
access/src
access/src/yang
access/src/yang/access.yang
access/src/Makefile
```
* Add service package to ```project-meta-data.xml```
```xml
<project-meta-data xmlns="http://tail-f.com/ns/ncs-project">
  <name>one-to-one-demo</name>
  <project-version>1.0</project-version>
  <description>Skeleton for a NCS project</description>

  <netsim>
    <device>
      <name>cisco-ios-cli-6.77</name>
      <prefix>cisco-ios-</prefix>
      <num-devices>4</num-devices>
    </device>
  </netsim>

  <package>
    <name>cisco-ios-cli-6.77</name>
    <local/>
  </package>

...truncated
  
  <package>
    <name>access</name>
    <local/>
  </package>

...truncated

</project-meta-data>
```

* Open YANG file ```packages/access/src/yang/access.yang``` and add/modify required sections:
```xml
module access {
  namespace "http://com/example/access";
  prefix access;

  import ietf-inet-types {
    prefix inet;
  }
  import tailf-ncs {
    prefix ncs;
  }

  list access {
    key customer-name;

    uses ncs:service-data;
    ncs:servicepoint "access";

    leaf customer-name {
      type string;
    }

    leaf-list device {
      type leafref {
        path "/ncs:devices/ncs:device/ncs:name";
      }
    }

    leaf vlan {
      mandatory true;
      type uint16;
    }

    leaf access-interface {
      mandatory true;
      type string;
    }

    leaf trunk-interface {
      mandatory true;
      type string;
    }
  }
}
```


* Edit Service Template ```packages/access/templates/access-template.xml``` and modify hard-coded names with the variables used in the YANG file.
```xml
<config-template xmlns="http://tail-f.com/ns/config/1.0" servicepoint="access">
  <devices xmlns="http://tail-f.com/ns/ncs">
    <device>
      <name>{/device}</name>
      <config>
        <policer xmlns="urn:ios">
          <aggregate>
            <name>{/customer-name}-pol</name>
            <cir>2000000</cir>
            <bc>375000</bc>
            <conform-action/>
            <transmit/>
            <exceed-action/>
            <drop/>
          </aggregate>
        </policer>
        <policy-map xmlns="urn:ios">
          <name>{/customer-name}</name>
          <class-default>
            <class>
              <name>class-default</name>
              <police-aggregate>
                <police>
                  <aggregate>{/customer-name}-pol</aggregate>
                </police>
              </police-aggregate>
            </class>
          </class-default>
        </policy-map>
        <vlan xmlns="urn:ios">
          <internal>
            <allocation>
              <policy>ascending</policy>
            </allocation>
          </internal>
          <dot1q>
            <tag>
              <native/>
            </tag>
          </dot1q>
          <vlan-list>
            <id>{/vlan}</id>
            <name>{/customer-name}</name>
          </vlan-list>
        </vlan>
        <interface xmlns="urn:ios">
          <GigabitEthernet>
            <name>{substring(/access-interface, 16)}</name>
            <description>{/customer-name}</description>
            <switchport>
              <access>
                <vlan>{/vlan}</vlan>
              </access>
            </switchport>
            <media-type>rj45</media-type>
            <mdix>
              <auto>false</auto>
            </mdix>
            <ethernet>
              <cfm>
                <interface>false</interface>
              </cfm>
            </ethernet>
            <speed>100</speed>
            <duplex>full</duplex>
            <snmp>
              <trap>
                <link-status>false</link-status>
              </trap>
            </snmp>
            <ip>
              <no-address>
                <address>false</address>
              </no-address>
            </ip>
            <service-policy>
              <input>{/customer-name}</input>
            </service-policy>
            <storm-control>
              <broadcast>
                <level>1.0</level>
              </broadcast>
            </storm-control>
          </GigabitEthernet>
          <GigabitEthernet>
            <name>{substring(/trunk-interface, 16)}</name>
            <port-type>nni</port-type>
            <switchport>
              <mode>
                <trunk/>
              </mode>
              <trunk>
                <allowed>
                  <vlan>
                    <vlans>1</vlans>
                    <vlans>{/vlan}</vlans>
                    <vlans>1000</vlans>
                  </vlan>
                </allowed>
              </trunk>
            </switchport>
            <udld>
              <port>
                <aggressive/>
              </port>
            </udld>
          </GigabitEthernet>
        </interface>
      </config>
    </device>
  </devices>
</config-template>
```
* Update Project
* Make ```access``` package
* Reload packages
```bash
❯ ncs-project update -y
❯ make -C packages/access/src
mkdir -p ../load-dir
/Users/janisa/nso-demo/bin/ncsc `ls access-ann.yang  > /dev/null 2>&1 && echo "-a access-ann.yang"` \
                --fail-on-warnings \
                 \
                -c -o ../load-dir/access.fxs yang/access.yang

❯ ncs_cli -u admin -C

admin@ncs# packages reload

>>> System upgrade is starting.
>>> Sessions in configure mode must exit to operational mode.
>>> No configuration changes can be performed until upgrade has completed.
>>> System upgrade has completed successfully.

reload-result {
    package access
    result true
}
reload-result {
    package cisco-ios-cli-6.77
    result true
}
reload-result {
    package cisco-iosxr-cli-7.38
    result true
}
reload-result {
    package cisco-nx-cli-5.22
    result true
}
admin@ncs# 
System message at 2022-12-01 14:02:29...
    Subsystem stopped: ncs-dp-7-cisco-ios-cli-6.77:IOSDp
admin@ncs# 
System message at 2022-12-01 14:02:29...
    Subsystem stopped: ncs-dp-8-cisco-nx-cli-5.22:NexusDp
admin@ncs# 
System message at 2022-12-01 14:02:29...
    Subsystem started: ncs-dp-9-cisco-ios-cli-6.77:IOSDp
admin@ncs# 
System message at 2022-12-01 14:02:29...
    Subsystem started: ncs-dp-10-cisco-nx-cli-5.22:NexusDp
admin@ncs#
admin@ncs# show packages package * oper-status
                                                                                                        PACKAGE                
                          PROGRAM                                                                       META     FILE          
                          CODE     JAVA           PYTHON         BAD NCS  PACKAGE  PACKAGE  CIRCULAR    DATA     LOAD   ERROR  
NAME                  UP  ERROR    UNINITIALIZED  UNINITIALIZED  VERSION  NAME     VERSION  DEPENDENCY  ERROR    ERROR  INFO   
-------------------------------------------------------------------------------------------------------------------------------
access                X   -        -              -              -        -        -        -           -        -      -      
cisco-ios-cli-6.77    X   -        -              -              -        -        -        -           -        -      -      
cisco-iosxr-cli-7.38  X   -        -              -              -        -        -        -           -        -      -      
cisco-nx-cli-5.22     X   -        -              -              -        -        -        -           -        -      -  
```
* Run ```access``` service against second device ```cisco-ios-1```
```bash
 admin@ncs# config
 admin@ncs(config)# access DemoUser device cisco-ios-1 vlan 500 access-interface GigabitEthernet0/10 trunk-interface GigabitEthernet0/1
```
* Run ```commit dry-run``` and verify config changes, then commit
```bash
admin@ncs(config-access-DemoUser)# commit dry-run 
cli {
    local-node {
        data  devices {
                  device cisco-ios-1 {
                      config {
                          policer {
             +                aggregate DemoUser-pol {
             +                    cir 2000000;
             +                    bc 375000;
             +                    conform-action;
             +                    transmit;
             +                    exceed-action;
             +                    drop;
             +                }
                          }
             +            policy-map DemoUser {
             +                class-default {
             +                    class class-default {
             +                        police {
             +                            aggregate DemoUser-pol;
             +                        }
             +                    }
             +                }
             +            }
                          vlan {
                              internal {
                                  allocation {
             +                        policy ascending;
                                  }
                              }
                              dot1q {
                                  tag {
             +                        native;
                                  }
                              }
             +                vlan-list 500 {
             +                    name DemoUser;
             +                }
                          }
                          interface {
                              GigabitEthernet 0/1 {
             +                    port-type nni;
             +                    switchport {
             +                        mode {
             +                            trunk {
             +                            }
             +                        }
             +                        trunk {
             +                            allowed {
             +                                vlan {
             +                                    vlans 500;
             +                                }
             +                            }
             +                        }
             +                    }
                                  ip {
                                      no-address {
             -                            address false;
                                      }
                                  }
                                  udld {
             +                        port {
             +                            aggressive;
             +                        }
                                  }
                              }
             +                GigabitEthernet 0/10 {
             +                    description DemoUser;
             +                    switchport {
             +                        access {
             +                            vlan 500;
             +                        }
             +                    }
             +                    media-type rj45;
             +                    mdix {
             +                        auto false;
             +                    }
             +                    ethernet {
             +                        cfm {
             +                            interface false;
             +                        }
             +                    }
             +                    speed 100;
             +                    duplex full;
             +                    snmp {
             +                        trap {
             +                            link-status false;
             +                        }
             +                    }
             +                    ip {
             +                        no-address {
             +                            address false;
             +                        }
             +                    }
             +                    service-policy {
             +                        input DemoUser;
             +                    }
             +                    storm-control {
             +                        broadcast {
             +                            level 1.0;
             +                        }
             +                    }
             +                }
                          }
                      }
                  }
              }
             +access DemoUser {
             +    device cisco-ios-1;
             +    vlan 500;
             +    access-interface GigabitEthernet0/10;
             +    trunk-interface GigabitEthernet0/1;
             +}
    }
}
admin@ncs(config-access-DemoUser)# commit
Commit complete.
```

* Navigate to Web UI at: http://127.0.0.1:8080
* Demonstarate Copying Service to additional device
* Modify Service parameters and apply configs to remaining devices
