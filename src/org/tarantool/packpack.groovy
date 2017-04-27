package org.tarantool;
import groovy.transform.Field
import com.cloudbees.groovy.cps.NonCPS

@Field List default_matrix = [
    [OS: 'el', DIST: '6', PACK: 'rpm'],
    //[OS: 'el', DIST: '7', PACK: 'rpm'],
    [OS: 'fedora', DIST: '24', PACK: 'rpm'],
    [OS: 'fedora', DIST: '25', PACK: 'rpm'],
    [OS: 'ubuntu', DIST: 'precise', PACK: 'deb'],
    [OS: 'ubuntu', DIST: 'trusty', PACK: 'deb'],
    [OS: 'ubuntu', DIST: 'xenial', PACK: 'deb'],
    [OS: 'ubuntu', DIST: 'yakkety', PACK: 'deb'],
    [OS: 'debian', DIST: 'jessie', PACK: 'deb'],
    [OS: 'debian', DIST: 'stretch', PACK: 'deb'],
]

@NonCPS
def printParams() {
  env.getEnvironment().each { name, value -> println "Name: $name -> Value $value" }
}

def previousBuildStatus(){
    return currentBuild.rawBuild.getPreviousBuild()?.getResult().toString()
}

def packpackBuildClosure(src_stash, params, failed) {
    return {
        node {
            ws {
                try {
                    deleteDir()
                    unstash src_stash
                    dir('packpack') {
                        git url: 'https://github.com/packpack/packpack.git'
                    }
                    sh 'git submodule update --init --recursive'

                    withEnv(["OS=${params['OS']}",
                             "DIST=${params['DIST']}",
                             "PACK=${params['PACK']}"]) {
                        sh 'echo building on $OS $DIST'
                        sh './packpack/packpack'
                    }

                    dst_stash = "${src_stash}-result-${params['OS']}-${params['DIST']}"
                    dir('build') {
                        stash dst_stash
                    }
                }
                catch(e)
                {
                    failed << "${params['OS']}-${params['DIST']}"
                    throw e
                }
            }
        }
    }
}

def sendEmail(is_success, failed_components) {
    job_name = env.JOB_NAME
    build_url = env.BUILD_URL
    build_log = "${build_url}console"
    changes_url = env.RUN_CHANGES_DISPLAY_URL
    prev_status = previousBuildStatus()

    if (prev_status == 'FAILURE' && is_success) {
        subject = "Fixed: ${job_name}"

        body = """
                | Build log: ${build_log}
                | Changes: ${changes_url}
            """.stripMargin()

        node {
            emailext body: body,
                recipientProviders: [[$class: 'DevelopersRecipientProvider'],
                                     [$class: 'RequesterRecipientProvider']],
                subject: subject
        }

    }

    if (!is_success)
    {
        if (prev_status == 'FAILURE') {
            subject = "Still failing: ${job_name}"
        }
        else {
            subject = "Failed: ${job_name}"
        }

        components_str = failed_components.join("\n")

        body = """
               |Failed components:
               |${components_str}
               |
               | Build log: ${build_log}
               | Changes: ${changes_url}
        """.stripMargin()

        node {
            emailext body: body,
                recipientProviders: [[$class: 'CulpritsRecipientProvider'],
                                     [$class: 'RequesterRecipientProvider']],
                subject: subject
        }
    }
}

def prepareSources() {
    src_stash = 'packpack-source'
    stash name: src_stash, useDefaultExcludes: false
}

def filterMatrix(matrix, closure) {
    def result = []

    for (int i = 0; i < matrix.size(); i++) {
        if (closure.call(matrix.get(i)))
        {
            result << matrix.get(i)
        }
    }
    return result
}

def packpackBuildMatrix(dst_stash, matrix=default_matrix) {
    def stepsForParallel = [:]

    src_stash = 'packpack-source'
    failed = []

    for (int i = 0; i < matrix.size(); i++) {
        def params = matrix.get(i)
        def stepName = "${params['OS']}-${params['DIST']}"
        stepsForParallel[stepName] = packpackBuildClosure(src_stash, params, failed)
    }

    try {
        parallel stepsForParallel
        sendEmail(true, failed)
    }
    catch(e) {
        sendEmail(false, failed)
        throw e
    }

    node {
        ws {
            deleteDir()
            for (int i = 0; i < matrix.size(); i++) {
                def params = matrix.get(i)
                def stash_name = "${src_stash}-result-${params['OS']}-${params['DIST']}"
                def dir_name = "${params['OS']}-${params['DIST']}"
                dir(dir_name) {
                    unstash stash_name
                }
            }
            stash dst_stash
        }
    }

}
