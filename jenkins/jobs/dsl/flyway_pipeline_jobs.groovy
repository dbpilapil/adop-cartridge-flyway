	// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def referenceAppGitRepo = "flyway-example"
def referenceAppGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + referenceAppGitRepo

// Jobs
def getSql = freeStyleJob(projectFolderName + "/Get_SQL")
def ciDeploy = freeStyleJob(projectFolderName + "/CI_Deploy")
def packageSql = freeStyleJob(projectFolderName + "/Package_SQL")
def stDeploy = freeStyleJob(projectFolderName + "/ST_Deploy")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Example_Flyway_Pipeline")

pipelineView.with{
    title('Example Flyway Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Get_SQL")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

getSql.with{
  description("This job downloads the SQL from Git.")
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  scm{
    git{
      remote{
        url(referenceAppGitUrl)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  triggers{
    gerrit{
      events{
        refUpdated()
      }
      configure { gerritxml ->
        gerritxml / 'gerritProjects' {
          'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject' {
            compareType("PLAIN")
            pattern(projectFolderName + "/" + referenceAppgitRepo)
            'branches' {
              'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch' {
                compareType("PLAIN")
                pattern("master")
              }
            }
          }
        }
        gerritxml / serverName("ADOP Gerrit")
      }
    }
  }
  label("docker")
  steps {
    shell('''set -xe
            |echo Pull the SQL from Git 
            |'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/CI_Deploy"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

ciDeploy.with{
  description("This job performs a test deployment of the SQL into a ephemeral DB")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_SQL","Parent build name")
  }
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
  label("docker")
  steps {
    copyArtifacts('Get_SQL') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo Spin up a DB in a container run FlyWay against it and then kill the container
            |docker run --name ci-mysql-instance -e MYSQL_ROOT_PASSWORD=password -d mysql:latest
	    |docker run -it --rm -v jenkins_slave_home:/jenkins_slave_home/ --entrypoint="dockerlint" shouldbee/flyway -f /jenkins_slave_home/$JOB_NAME/Dockerfile
            |'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Build"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${JOB_NAME}')
        }
      }
    }
  }
}


packageSql.with{
  description("This job will package the SQL for use in other environments")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_SQL","Parent build name")
  }
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
  label("docker")
  steps {
    copyArtifacts('Get_SQL') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |zip '''.stripMargin() + 
	    |referenceAppGitRepo + '''.zip .'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/ST_Deploy"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

stDeploy.with{
  description("When triggered this will deploy to the ST environment.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_SQL","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_SQL") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set +x
            |echo This will deploy to ST
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

