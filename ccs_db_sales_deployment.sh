set +x
echo "Pipeline ID is ${B}"

echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. 
echo =.=.=.=. Initiating Deployment =.=.=.=. 
echo

resolvedfile=${WORKSPACE}/${ENVIRONMENT}.resolved
set -e
set -x
dpluser=`${jenkins_tools_repo}/jenkins/shell/getopts.sh $resolvedfile username.deployment`
dpldir=`${jenkins_tools_repo}/jenkins/shell/getopts.sh $resolvedfile directory.deployment`
dpldir=${dpldir}/${ENVIRONMENT}
dbserver=`${jenkins_tools_repo}/jenkins/shell/getopts.sh $resolvedfile host.database.ccs_sales.deployment`
parentandbid=${PARENT_BUILD}_${B}
packageprefix=${PARENT_BUILD}

set +x
echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. 
echo =.=.=.=. Fetch build package =.=.=.=. 
echo

find ${jenkins_jobs_directory}/${PARENT_BUILD}/builds/ -name *_${B}.zip -exec cp {} . \;

echo "Files in the Workspace"

ls -l 

mkdir exploded

for file in ./*
do
 
 if [[ $file == *.zip ]]
then
 unzip $file -d exploded/$file
fi

done


set +x
echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. 
echo =.=.=.=. Tokenisation Process =.=.=.=. 
echo
set -x

cd ${WORKSPACE}

perl -e 'open (MYFILELIST, ">", "files_to_tokenise.txt") || die $!;my $targetFiles=`find . -name *.template`; print MYFILELIST "file=$targetFiles";close MYFILELIST;'
 
${jenkins_tools_repo}/deployment/token_resolver_template.pl --tokenFile=${jenkins_envfiles_repo}/${ENVIRONMENT}.properties --fileList=files_to_tokenise.txt --force

set +x
echo =.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.
echo =.=.=.=. Zipping up Deployment files  =.=.=.=. 
echo
set -x

if [[ ! -e ${WORKSPACE}/tokenised ]]
then
    mkdir ${WORKSPACE}/tokenised
fi


cd ${WORKSPACE}/exploded


for zipfile in ./*
do
 
if [[ $zipfile == *.zip ]]
then
   if [[ -e ${WORKSPACE}/tokenised/$zipfile ]]
   then
          echo clearing down old zip file
          rm ${WORKSPACE}/tokenised/$zipfile  
   fi

cd ${WORKSPACE}/exploded/$zipfile
zip -r ${WORKSPACE}/tokenised/$zipfile ./*

fi
done


set +x
echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. 
echo =.=.=.=. Flyway Deployment =.=.=.=. 
echo
set -x

# Attempt to either create the deployment directory or, if it is there, get the previous build number for the release note 
# For the Release note
set +e
PBZIP=`set -e; ssh ${dpluser}@${dbserver} "if [ ! -e ${dpldir} ]; then mkdir -p ${dpldir}; elif [ -e ${dpldir}/${packageprefix}*.zip ]; then ls ${dpldir}/${packageprefix}*.zip; fi;"`
PB= `echo $PBZIP | sed 's/${dpldir}\///' | sed 's/\.zip//'`
set -e

echo $PB

echo "Remove previous deployment package, if any"
ssh  ${dpluser}@${dbserver} << EOF
set -x

if [[ -e ${dpldir}/${packageprefix}_${B} ]]; then rm -r ${dpldir}/${packageprefix}*; fi;

EOF

# Copy in the new deployment package

scp  ${WORKSPACE}/tokenised/*.zip ${dpluser}@${dbserver}:${dpldir}

# Unzip it and run flyway
ssh  ${dpluser}@${dbserver} << EOF
set -e
cd ${dpldir};
unzip ${parentandbid}.zip -d ${parentandbid};
EOF

if [ $REBUILD_SALES_SCHEMA == "true" ] 
then 
	ssh  ${dpluser}@${dbserver} << EOF
	set -e
	set +x
	echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. 
	echo =.=.=.=. Flyway Clear Down  Schema =.=.=.=. 
	echo
	set -x
	cd ${dpldir}/${parentandbid}/tools/flyway-commandline-2.1.1/
	export TERM=xterm
	set +x
	./flyway.sh clean $ADDITIONAL_FW_ARGS
EOF
fi

ssh  ${dpluser}@${dbserver} << EOF
set -e
set +x
echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=. 
echo =.=.=.=. Flyway Deployment =.=.=.=. 
echo
set -x

cd ${dpldir}/${parentandbid}/tools/flyway-commandline-2.1.1/

export TERM=xterm
set +x
echo +++++++++++++++++++++++++++++++++++++
echo ++++++ Handling $sql_subfolder ++++++

echo ++++ Previewing Pending Changes For Incremental Deploy ++++
echo 
set -x
./flyway.sh info $ADDITIONAL_FW_ARGS
set +x

echo ++++ Validating applied versions ++++
echo
set -x
./flyway.sh validate $ADDITIONAL_FW_ARGS
set +x

echo ++++ Perform an incremental deployment using FlyWay ++++
echo

set -x
./flyway.sh migrate $ADDITIONAL_FW_ARGS
set +x

echo ++++ Review status ++++
echo

set -x
./flyway.sh info $ADDITIONAL_FW_ARGS
set +x

echo ++++++ Done ++++++

EOF

set +x
echo =.=.=.=. =.=.=.=. =.=.=.=. =.=.=.=.=.=.=.=. 
echo =.=.=.=. 		Release Note		=.=.=.=. 
echo
set -x

set +e

rn_name=DPL_${BUILD_NUMBER}_of_${PB}_to_${parentandbid}_into_${ENVIRONMENT}_release_note.txt

echo Generating Release Note
echo

echo === RELEASE NOTE === >> $rn_name
echo
echo DEPLOYMENT: $BUILD_URL >> $rn_name
echo FROM BUILD: ${PB} >> $rn_name
echo TO BUILD: ${parentandbid} >> $rn_name
echo ENVIRONMENT: ${ENVIRONMENT} >> $rn_name
echo >> $rn_name
echo >> $rn_name
echo CHANGES: >> $rn_name

git --git-dir=${jenkins_jobs_directory}/${PARENT_BUILD}/workspace/.git   log ${PB}..${parentandbid} --pretty=format:%s%x09%ae | sort -u | tee -a $rn_name

git --git-dir=${jenkins_jobs_directory}/${PARENT_BUILD}/workspace/.git  log ${PB}..${parentandbid} --pretty=format:%ae | sort -u | sed ':a;N;$!ba;s/\n/ /g' | tee contrib_emails.txt

echo >> $rn_name
echo >> $rn_name
echo CONTRIBUTORS: >> $rn_name
cat contrib_emails.txt >> $rn_name
