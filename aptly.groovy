/**
 * Upload package into local repo
 *
 * @param file          File path
 * @param server        Server host
 * @param repo          Repository name
 */
def uploadPackage(file, server, repo) {
    def pkg = file.split('/')[-1].split('_')[0]
    def jobName = currentBuild.build().environment.JOB_NAME

    sh("curl -v -f -F file=@${file} ${server}/api/files/${pkg}")
    sh("curl -v -o curl_out_${pkg}.log -f -X POST ${server}/api/repos/${repo}/file/${pkg}")

    try {
        sh("cat curl_out_${pkg}.log | json_pp | grep 'Unable to add package to repo' && exit 1")
    } catch (err) {
        sh("curl -s -f -X DELETE ${server}/api/files/${pkg}")
        error("Package ${pkg} already exists in repo, did you forget to add changelog entry and raise version?")
    }
}

/**
 * Build step to upload package. For use with eg. parallel
 *
 * @param file          File path
 * @param server        Server host
 * @param repo          Repository name
 */
def uploadPackageStep(file, server, repo) {
    return {
        uploadPackage(
            file,
            server,
            repo
        )
    }
}

def snapshotRepo(server, repo, timestamp) {
    def snapshot = "${repo}-${timestamp}"
    sh("curl -f -X POST -H 'Content-Type: application/json' --data '{\"Name\":\"$snapshot\"}' ${server}/api/repos/${repo}/snapshots")
}

def cleanupSnapshots(server, config='/etc/aptly-publisher.yaml', opts='-d --timeout 600') {
    sh("aptly-publisher -c ${config} ${opts} --url ${server} cleanup")
}

def diffPublish(server, source, target, components=null, opts='--timeout 600') {
    if (components) {
        def componentsStr = components.join(' ')
        opts = "${opts} --components ${componentsStr}"
    }
    sh("aptly-publisher --dry --url ${server} promote --source ${source} --target ${target} --diff ${opts}")
}

def promotePublish(server, source, target, recreate=false, components=null, packages=null, opts='-d --timeout 600') {
    if (components) {
        def componentsStr = components.join(' ')
        opts = "${opts} --components ${componentsStr}"
    }
    if (packages) {
        def packagesStr = packages.join(' ')
        opts = "${opts} --packages ${packagesStr}"
    }
    if (recreate == true) {
        opts = "${opts} --recreate"
    }
    sh("aptly-publisher --url ${server} promote --source ${source} --target ${target} ${opts}")
}

def publish(server, config='/etc/aptly-publisher.yaml', recreate=false, opts='-d --timeout 600') {
    if (recreate == true) {
        opts = "${opts} --recreate"
    }
    sh("aptly-publisher --url ${server} -c ${config} ${opts} publish")
}

return this;
