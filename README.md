<p align="center">
    <img src="/tarantool_jenkins.png" width="400"/>
</p>


# Packpack and Jenkins integration for Tarantool

This repo contains scripts to make Tarantool build job definitions
in [Jenkins](https://jenkins.io) easier.

Read further to get an idea why we decided to try Jenkins and how we
incorporated [Travis](https://travis-ci.org)-like build matrices
there.


## Quickstart

To start building a new package on Jenkins, add a file called
"Jenkinsfile" to its root with the following content:

``` groovy
stage('Build'){
    packpack = new org.tarantool.packpack()
    node {
        checkout scm
        packpack.prepareSources()
    }

    packpack.packpackBuildMatrix('result')
}
```

You don't need to do anything else: Jenkins will find the new repo
itself by the presence of Jenkinsfile.


## Tarantool's CI needs

We build Tarantool and run its tests on all common Linux
distributions. This helps us make sure that degradations are noticed
as early as possible. Unfortunately, this also means we have to
maintain a large build matrix with various distros and their versions.
This matrix is applied to Tarantool itself, and to about 25 official
packages.

In addition to building source code and running unit tests, we also
run performance tests, which require physical hardware and no outside
interferrence in the process.

While [Travis CI](https://travis-ci.org) works fine for building and
unit tests, it just won't guarantee you get equally powerful VMs or
that there is no CPU/disk hogs running in parallel on the same
physical machine. For this reason, we were looking for a way to
replicate Travis functionality on-premise.


## Intro to PackPack

A while ago we built [PackPack](https://github.com/packpack/packpack),
which helps you abstract away the process of running a specific Linux
distro and building packages there. It spins up a temporary Docker
container with the required OS, mounts code directory from host and
builds a package.

Originally we built PackPack to simplify Travis CI configuration, and
being able to use commands like this:

``` bash
OS=ubuntu DIST=xenial ./packpack/packpack
```

As Travis has support for environment variable matrix, you can specify
multiple parallel steps like this:

``` yaml
env:
    matrix:
      - OS=el DIST=6
      - OS=el DIST=7
      - OS=ubuntu DIST=precise
      - OS=ubuntu DIST=trusty
      - OS=ubuntu DIST=xenial
```

This makes writing build "recipes" easy and clean. And this is what we
want to replicate in our on-premises CI system.


## How Jenkins works

As with many other CI systems out there, [Jenkins](https://jenkins.io)
in its core is a complicated RPC framework that allows you to execute
commands on remote machines that have Jenkins agent installed. Jenkins
itself and its agent are written in Java, so they are portable across
pretty much every OS.

Jenkins became popular long time ago, because it provided a way to
quickly configure scheduled builds of your product via a web UI. It
was so convenient that for some time it was a de-facto standard of
Continuous Integration. Due to this, over time Jenkins accumulated
tons of plugins that make it work with all sorts of external systems.


## Pipeline

After Travis CI appeared, it became apparent that storing build
scripts in the product repository incapsulates the build process a lot
better than configuring it via the web UI separately. And authors of
Jenkins started working on bringing similar functionality on board.

There solution they proposed was a thing
called [Pipeline](https://jenkins.io/doc/pipeline). As opposed to
Travis, which stores its build configuration in Yaml files, Pipelines
are scripts in a high-level programming language
called [Groovy](http://groovy-lang.org) (popular on Java platforms).
To make things easier for end-users, the authors of Jenkins added
convenient DSL methods, so it started to look like this:

``` groovy
pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                sh 'make'
            }
        }
        stage('Test'){
            steps {
                sh 'make check'
                junit 'reports/**/*.xml'
            }
        }
        stage('Deploy') {
            steps {
                sh 'make publish'
            }
        }
    }
}
```


It may look high-level, but in fact everything in the script is just
code blocks and function calls. And you are free to use other language
features, such as loops and conditions.

Inside it's implemented using CPS-style concurrency, which is akin to
how fibers/greenlets work in some languages. It means that you can run
your code and build jobs in parallel and have flexible control over
the build process.


## GitHub integration

Pipeline scripts used to be written in Jenkins UI, as with old-style
build jobs, but then there appeared
[integration with git](https://jenkins.io/blog/2015/12/03/pipeline-as-code-with-multibranch-workflows-in-jenkins/)
(and other SCM systems), and
[with GitHub](https://github.com/jenkinsci/github-organization-folder-plugin).

Now, you just need to point Jenkins to the GitHub organization and it
scans projects and all their branches looking for a file called
"Jenkinsfile", which it will use to build those specific branches.

In a sense this is pretty much how Travis CI works, except the
pipeline script is written in Groovy, instead of Yaml.


## DRY: a build matrix + Jenkins pipeline

A problem with Jenkins Pipeline scripts is that they may be wordy. Compare how I did it in the beginning:

``` groovy
pipeline {
    parallel (
        "el6": {
            agent
            deleteDir()
            checkout scm
            env.OS="el"; env.DIST="6"; env.PACK="rpm"
            sh './packpack/packpack'
        },
        "el7": {
            agent
            deleteDir()
            checkout scm
            env.OS="el"; env.DIST="7"; env.PACK="rpm"
            sh './packpack/packpack'
        },
    )
}
```

And how the same will look for Travis:

``` yaml
env:
  matrix:
    - OS=el DIST=6
    - OS=el DIST=7

script:
  - packpack/packpack
```


It is obvious that the way Pipeline does it is a bit too wordy and
repetitive. Fortunately, as it is a programming language, it is
possible to write a function to abstract away the repetitive parts.

It also turned out that Jenkins allows us to load a code library with
such abstractions to be globally accessible from all Pipeline scripts.
One just needs to set in the Jenkins configuration the git repo
containing reusable Groovy code.

After writing a set of convenience functions, we made Jenkinsfile look
like this:

``` groovy
matrix = [
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

stage('Build'){
    packpack = new org.tarantool.packpack()
    node {
        checkout scm
        packpack.prepareSources()
    }

    packpack.packpackBuildMatrix('result', matrix)
}
```


To further simplify configuration, we made build matrix an optional
argument to packpackBuildMatrix. If it's not specified, the default
one is used:

``` groovy
stage('Build'){
    packpack = new org.tarantool.packpack()
    node {
        checkout scm
        packpack.prepareSources()
    }

    packpack.packpackBuildMatrix('result')
}
```


Which makes build configuration even shorter and cleaner than Travis'
and makes adding new distros to the build matrix easy: you just have
to change build matrix in this repo and commit it. And then all repos
that made use of default matrix will automatically start building on
the new platform. It is a lot better than having to go over every repo
and add new platform to each .travis.yml file.


## How to add matrix builds to a new component

So, as it has been said in the introduction, all you have to do to
start building your component is to add "Jenkinsfile" to the
repository root with the following content:

``` groovy
stage('Build'){
    packpack = new org.tarantool.packpack()
    node {
        checkout scm
        packpack.prepareSources()
    }

    packpack.packpackBuildMatrix('result')
}
```


One catch, though: there are some packages that can't be built on some
platforms due to unmet dependencies. In such case you should use
filterMatrix function that removes those platforms from the build
matrix. Example for "avro-schema":

``` groovy
stage('Build'){
    packpack = new org.tarantool.packpack()

    // Ubuntu precise has old gcc that can't build phf library
    matrix = packpack.filterMatrix(
        packpack.default_matrix,
        {!(it['OS'] == 'ubuntu' && it['DIST'] == 'precise')})

    node {
        checkout scm
        packpack.packpackBuildMatrix('result', matrix)
    }
}
```


Please refrain from specifying build matrix by hand in this case, as
you'll loose the bonus of the simplified addition of new build OS-s.


## Credits

Big thanks to [[https://www.cloudbees.com][CloudBees]] for creating
Jenkins and being awesome in general.
