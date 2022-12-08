#!/bin/bash


USRSHELL=$(basename $SHELL)
echo -e "\nDetected Shell: $USRSHELL\n"

USRPROFILE=$HOME/'.'${USRSHELL}'rc'
echo -e "Assuming RC File: $USRPROFILE\n"

NCSRCFILE=$(prev=.; while [[ $PWD != "$prev" ]] ; do find "$PWD" -maxdepth 1 -name "ncsrc"; prev=$PWD; cd ..; done)

echo -e "Detected NCS Source File: $NCSRCFILE\n"
SOURCECMD="source $NCSRCFILE"

CHECKSRC=$(grep -e "source $NCSRCFILE" $USRPROFILE)
CHECKNCS=$(grep -e "ncsrc" $USRPROFILE)

if [[ ! $CHECKSRC ]]; then
    if [[ ! $CHECKNCS ]]; then
            echo -e "Appending \"$SOURCECMD\" to \"$USRPROFILE\"\n"
            echo "$SOURCECMD" >> $USRPROFILE
        else
            echo -e "Found \"$CHECKNCS\" in \"$USRPROFILE\"\n\nReplacing with \"$SOURCECMD\"\n"
            sed -i '' 's|^.*ncsrc$|source '$NCSRCFILE'|g' $USRPROFILE
        fi
    else
        echo -e "Found \"$CHECKSRC\" in \"$USRPROFILE\"\n"
        exit 0
fi

echo -e "Reloading $USRSHELL profile\n"
exec $SHELL -l

exit 0


