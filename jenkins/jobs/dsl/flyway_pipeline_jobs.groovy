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
            |
            |docker rm -f ci-mysql-instance || true
            |
            |docker network rm ci-net || true
            |docker network create ci-net
            |
            |docker run -v /var/run/docker.sock:/var/run/docker.sock \\
            |--net=ci-net \\
            |--name ci-mysql-instance \\
            |-e MYSQL_ROOT_PASSWORD=password \\
            |-e MYSQL_DATABASE=ci \\
            |-d mysql:latest
            |
            |sleep 10s
            |
            |printf "Preview!\n"
            |docker run -v /var/run/docker.sock:/var/run/docker.sock \\
            |--rm -v jenkins_slave_home:/jenkins_slave_home/ \\
            |--net=ci-net \\
            |shouldbee/flyway \\
            |-locations=filesystem:/jenkins_slave_home/$JOB_NAME/src/main/resources/sql/migrations/ \\
            |-url=jdbc:mysql://ci-mysql-instance/ci -user=root -password=password info
            |
            |printf "Executing the scripts!\n"
            |docker run -v /var/run/docker.sock:/var/run/docker.sock \\
            |--rm -v jenkins_slave_home:/jenkins_slave_home/ \\
            |--net=ci-net \\
            |shouldbee/flyway \\
            |-locations=filesystem:/jenkins_slave_home/$JOB_NAME/src/main/resources/sql/migrations/ \\
            |-url=jdbc:mysql://ci-mysql-instance/ci -user=root -password=password migrate
            |
            |printf "End state!\n"
            |docker run -v /var/run/docker.sock:/var/run/docker.sock \\
            |--rm -v jenkins_slave_home:/jenkins_slave_home/ \\
            |--net=ci-net \\
            |shouldbee/flyway \\
            |-locations=filesystem:/jenkins_slave_home/$JOB_NAME/src/main/resources/sql/migrations/ \\
            |-url=jdbc:mysql://ci-mysql-instance/ci -user=root -password=password info
            |
            |docker rm -f ci-mysql-instance
            |docker network rm ci-net
            |'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Package_SQL"){
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
            |zip -r '''.stripMargin() +
            |referenceAppGitRepo + ${B}.zip'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*zip")
    downstreamParameterized{
      trigger(projectFolderName + "/ST_Deploy"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
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
    copyArtifacts("Package_SQL") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set +x
            |echo This would deploy to ST
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

