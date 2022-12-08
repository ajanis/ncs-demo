MAIN_YANG_MODULE = tailf-ned-cisco-asa.yang
PACKAGE_NAME=asa

all: rm_package_meta_data all_cli

rm_package_meta_data:
	rm -f ../package-meta-data.xml

include nedcom.mk
ncsc-out/modules/fxs/ietf-ip.fxs: ncsc-out/modules/fxs/ietf-interfaces.fxs
