/**
 * Return workspace.
 * Currently implemented by calling pwd so it won't return relevant result in
 * dir context
 */
def getWorkspace() {
    def workspace = sh script: 'pwd', returnStdout: true
    workspace = workspace.trim()
    return workspace
}

def cleanup(image="debian:sid") {
    def img = docker.image(image)

    workspace = getWorkspace()
    sh("docker run -e DEBIAN_FRONTEND=noninteractive -v ${workspace}:${workspace} -w ${workspace} --rm=true --privileged ${image} /bin/bash -c 'rm -rf build-area || true'")
}

/*
 * Build binary Debian package from existing dsc
 *
 * @param file  dsc file to build
 * @param image Image name to use for build (default debian:sid)
 */
def buildBinary(file, image="debian:sid", extraRepoUrl=null, extraRepoKeyUrl=null) {
    def pkg = file.split('/')[-1].split('_')[0]
    def img = docker.image(image)

    workspace = getWorkspace()
    sh("""docker run -e DEBIAN_FRONTEND=noninteractive -v ${workspace}:${workspace} -w ${workspace} --rm=true --privileged ${image} /bin/bash -c '
            which eatmydata || (apt-get update && apt-get install -y eatmydata) &&
            export LD_LIBRARY_PATH=\${LD_LIBRARY_PATH:+"\$LD_LIBRARY_PATH:"}/usr/lib/libeatmydata &&
            export LD_PRELOAD=\${LD_PRELOAD:+"\$LD_PRELOAD "}libeatmydata.so &&
            [[ -z "${extraRepoUrl}" && "${extraRepoUrl}" != "null" ]] || echo "${extraRepoUrl}" >/etc/apt/sources.list.d/extra.list &&
            [[ -z "${extraRepoKeyUrl}" && "${extraRepoKeyUrl}" != "null" ]] || (
                which curl || (apt-get update && apt-get install -y curl) &&
                curl --insecure -ss -f "${extraRepoKeyUrl}" | apt-key add -
            ) &&
            apt-get update && apt-get install -y build-essential devscripts equivs &&
            dpkg-source -x ${file} build-area/${pkg} && cd build-area/${pkg} &&
            mk-build-deps -t "apt-get -o Debug::pkgProblemResolver=yes -y" -i debian/control
            debuild --no-lintian -uc -us -b'""")
}

/*
 * Build source package from directory
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 * @param snapshot Generate snapshot version (default false)
 */
def buildSource(dir, image="debian:sid", snapshot=false, gitEmail='jenkins@dummy.org', gitName='Jenkins') {
    def isGit
    try {
        sh("test -d ${dir}/.git")
        isGit = true
    } catch (Exception e) {
        isGit = false
    }

    if (isGit == true) {
        buildSourceGbp(dir, image, snapshot, gitEmail='jenkins@dummy.org', gitName='Jenkins')
    } else {
        buildSourceUscan(dir, image)
    }
}

/*
 * Build source package, fetching upstream code using uscan
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 */
def buildSourceUscan(dir, image="debian:sid") {
    def img = docker.image(image)
    workspace = getWorkspace()
    sh("""docker run -e DEBIAN_FRONTEND=noninteractive -v ${workspace}:${workspace} -w ${workspace} --rm=true --privileged ${image} /bin/bash -c '
            apt-get update && apt-get install -y build-essential devscripts &&
            cd ${dir} && uscan --download-current-version &&
            dpkg-buildpackage -S -nc -uc -us'""")
}

/*
 * Build source package using git-buildpackage
 *
 * @param dir   Tree to build
 * @param image Image name to use for build (default debian:sid)
 * @param snapshot Generate snapshot version (default false)
 */
def buildSourceGbp(dir, image="debian:sid", snapshot=false, gitEmail='jenkins@dummy.org', gitName='Jenkins') {
    def img = docker.image(image)
    workspace = getWorkspace()
    sh("""docker run -e DEBIAN_FRONTEND=noninteractive -e DEBFULLNAME='${gitName}' -e DEBEMAIL='${gitEmail}' -v ${workspace}:${workspace} -w ${workspace} --rm=true --privileged ${image} /bin/bash -exc '
            which eatmydata || (apt-get update && apt-get install -y eatmydata) &&
            export LD_LIBRARY_PATH=\${LD_LIBRARY_PATH:+"\$LD_LIBRARY_PATH:"}/usr/lib/libeatmydata &&
            export LD_PRELOAD=\${LD_PRELOAD:+"\$LD_PRELOAD "}libeatmydata.so &&
            apt-get update && apt-get install -y build-essential git-buildpackage &&
            cd ${dir} &&
            git config --global user.name "${gitName}" &&
            git config --global user.email "${gitEmail}" &&
            [[ "${snapshot}" == "false" ]] || (
                VERSION=`dpkg-parsechangelog --count 1 | grep Version: | sed "s,Version: ,,g"` &&
                UPSTREAM_VERSION=`echo \$VERSION | cut -d "-" -f 1` &&
                REVISION=`echo \$VERSION | cut -d "-" -f 2` &&
                TIMESTAMP=`date +%Y%m%d%H%M` &&
                if [[ "`cat debian/source/format`" = *quilt* ]]; then
                    UPSTREAM_BRANCH=`(grep upstream-branch debian/gbp.conf || echo master) | cut -d = -f 2` &&
                    UPSTREAM_REV=`git rev-parse --short \$UPSTREAM_BRANCH` &&
                    NEW_UPSTREAM_VERSION="\$UPSTREAM_VERSION+\$TIMESTAMP.\$UPSTREAM_REV" &&
                    NEW_VERSION=\$NEW_UPSTREAM_VERSION-\$REVISION &&
                    echo "Generating new upstream version \$NEW_UPSTREAM_VERSION" &&
                    git tag \$NEW_UPSTREAM_VERSION \$UPSTREAM_BRANCH &&
                    git merge -X theirs \$NEW_UPSTREAM_VERSION
                else
                    NEW_VERSION=\$VERSION+\$TIMESTAMP.`git rev-parse --short HEAD`
                fi &&
                gbp dch --auto --multimaint-merge --ignore-branch --new-version=\$NEW_VERSION --distribution `lsb_release -c -s` --force-distribution &&
                git add -u debian/changelog && git commit -m "New snapshot version \$NEW_VERSION"
            ) &&
            gbp buildpackage -nc --git-force-create --git-notify=false --git-ignore-branch --git-ignore-new --git-verbose --git-export-dir=../build-area -S -uc -us'""")
}

/*
 * Run lintian checks
 *
 * @param changes   Changes file to test against
 * @param profile   Lintian profile to use (default debian)
 * @param image     Image name to use for build (default debian:sid)
 */
def runLintian(changes, profile="debian", image="debian:sid") {
    def img = docker.image(image)
    workspace = getWorkspace()
    sh("""docker run -e DEBIAN_FRONTEND=noninteractive -v ${workspace}:${workspace} -w ${workspace} --rm=true --privileged ${image} /bin/bash -c '
            apt-get update && apt-get install -y lintian &&
            lintian -Ii -E --pedantic --profile=${profile} ${changes}'""")
}

return this;
