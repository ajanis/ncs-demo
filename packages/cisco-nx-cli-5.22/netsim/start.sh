env sname=${NAME} ${CONFD} -c confd.conf --addloadpath ${CONFD_DIR}/etc/confd ${CONFD_FLAGS}
confd_cmd -c 'mset /aaa:aaa/ios/level{0}/prompt "'${NAME}'> "'
confd_cmd -c 'mset /aaa:aaa/ios/level{15}/prompt "'${NAME}'# "'
