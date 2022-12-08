#!/bin/bash

if [ $1 = 'xml' ]; then
    CMD=""
else
    CMD="show"
fi

for c in $@ ; do CMD=$CMD"_"$c ; done

CMD=`echo $CMD | sed -e 's/\//_/g'`

if [[ $CMD == _xml_* ]]; then
    CMD=${CMD:5}".xml"
else
    CMD=$CMD".txt"
fi

if [ -f ./$CMD ]; then
    cat ./$CMD
else
cat <<EOF
                 ^
% Invalid command at '^' marker.
EOF
fi

