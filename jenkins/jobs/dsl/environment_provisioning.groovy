// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables

// Jobs
def createEnvironmentJob = freeStyleJob(projectFolderName + "/Create_Environment")
def destroyEnvironmentJob = freeStyleJob(projectFolderName + "/Destroy_Environment")
def listEnvironmentJob = freeStyleJob(projectFolderName + "/List_Environment")

// Views
def environmentProvisioningPipelineView = buildPipelineView(projectFolderName + "/Environment_Provisioning")

// Pipeline
environmentProvisioningPipelineView.with{
    title('Environment (DB) Provisioning Pipeline')
    displayedBuilds(5)
    selectedJob("Create_Environment")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

// Create Environment
createEnvironmentJob.with{
    description('''This job creates extra databases for deploying to.''')
    parameters {
        predefinedProp('ENVIRONMENT_NAME', 'ST')
    }
    label("docker")
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''set +x
            |echo Spin up a DB 
            |
            |MYSQL_CONT=${ENVIRONMENT_NAME}
            |MYSQL_NET=${ENVIRONMENT_NAME}_network
            |
            |docker network create $MYSQL_NET
            |
            |docker run -v /var/run/docker.sock:/var/run/docker.sock \\
            |--net=$MYSQL_NET \\
            |--name ${MYSQL_CONT} \\
            |-e MYSQL_ROOT_PASSWORD=password \\
            |-e MYSQL_DATABASE=${ENVIRONMENT_NAME} \\
            |-l ROLE=FLYWAYCARTDB \\
            |-d mysql:latest
            |

            |while ! docker run --rm --net=$MYSQL_NET mysql:latest mysql -h $MYSQL_CONT -u root --password=password -e 'show databases;' 
            |do
            |echo Waiting for MYSQL to come up
            |sleep 3
            |done
            |set -x'''.stripMargin())
    }
    publishers {
        buildPipelineTrigger("${PROJECT_NAME}/Destroy_Environment") {
            parameters {
                currentBuild()
            }
        }
    }
}

// Run the create environment job once so that the ST environment exists
queue(createEnvironmentJob)

// Destroy Environment
destroyEnvironmentJob.with{
    description("This job deletes the environment.")
    parameters {
        predefinedProp('ENVIRONMENT_NAME', 'ST')
    }
    label("docker")
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''set +x
            |echo Remove the a DB 
            |
            |MYSQL_CONT=${ENVIRONMENT_NAME}
            |MYSQL_NET=${ENVIRONMENT_NAME}_network
            |docker rm -f $MYSQL_CONT
            |docker network rm $MYSQL_NET
            |set -x'''.stripMargin())
    }
}

// Create Environment
listEnvironmentJob.with{
    description("This job list the running environments (databases).")
    label("docker")
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''set +x
                |docker ps --filter status=running --filter "label=ROLE=FLYWAYCARTDB"
                |echo "=.=.=.=.=.=.=.=.=.=.=.=."
                |echo "=.=.=.=.=.=.=.=.=.=.=.=."
                |echo "List of running environments -"
                |docker ps --filter status=running --filter "label=ROLE=FLYWAYCARTDB" --format "\t{{.Names}}"
                |echo "=.=.=.=.=.=.=.=.=.=.=.=."
                |echo "=.=.=.=.=.=.=.=.=.=.=.=."
                |set -x'''.stripMargin())
    }
}
