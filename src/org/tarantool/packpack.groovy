package org.tarantool;
import groovy.transform.Field

@Field List default_matrix = [
    [OS: 'el', DIST: '6', PACK: 'rpm'],
    [OS: 'el', DIST: '7', PACK: 'rpm'],
    [OS: 'fedora', DIST: '24', PACK: 'rpm'],
    [OS: 'fedora', DIST: '25', PACK: 'rpm'],
    [OS: 'fedora', DIST: 'rawhide', PACK: 'rpm'],
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
                    sh 'git submodule update --init'

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

def packpackBuildMatrix(dst_stash, matrix=default_matrix) {
    def stepsForParallel = [:]

    src_stash = 'packpack-source'
    stash name: src_stash, useDefaultExcludes: false

    failed = []

    for (int i = 0; i < matrix.size(); i++) {
        def params = matrix.get(i)
        def stepName = "${params['OS']}-${params['DIST']}"
        stepsForParallel[stepName] = packpackBuildClosure(src_stash, params, failed)
    }

    try {
        parallel stepsForParallel
    }
    catch(e) {
        echo "failed steps: ${failed}"
        printParams()
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
