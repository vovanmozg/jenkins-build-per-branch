package com.entagen.jenkins

import java.util.regex.Pattern

class JenkinsJobManager {
    String templateJobPrefix
    String templateBranchName
    String gitUrl
    String nestedView
    String jenkinsUrl
    String branchNameRegex
    String viewRegex
    String jenkinsUser
    String jenkinsPassword
    
    Boolean dryRun = false
    Boolean noViews = false
    Boolean noDelete = false
    Boolean startOnCreate = false

    JenkinsApi jenkinsApi
    GitApi gitApi

    JenkinsJobManager(Map props) {
        for (property in props) {
            this."${property.key}" = property.value
        }
        initJenkinsApi()
        initGitApi()
    }

    void syncWithRepo() {
        //println "DEBUG: syncWithRepo"
        List<String> allBranchNames = gitApi.branchNames
        List<String> allJobNames = jenkinsApi.jobNames

        // ensure that there is at least one job matching the template pattern, collect the set of template jobs
        List<TemplateJob> templateJobs = findRequiredTemplateJobs(allJobNames)

        // create any missing template jobs and delete any jobs matching the template patterns that no longer have branches
        syncJobs(allBranchNames, allJobNames, templateJobs)

        // create any missing branch views, scoped within a nested view if we were given one
        if (!noViews) {
            syncViews(allBranchNames)
        }
    }

    public void syncJobs(List<String> allBranchNames, List<String> allJobNames, List<TemplateJob> templateJobs) {
        //println "DEBUG: syncJobs()"
        //println "DEBUG: allBranchNames = $allBranchNames"
        //println "DEBUG: allJobNames = $allJobNames"
        //println "DEBUG: templateJobs = $templateJobs"
        List<String> currentTemplateDrivenJobNames = templateDrivenJobNames(templateJobs, allJobNames)
        //println "DEBUG: currentTemplateDrivenJobNames = $currentTemplateDrivenJobNames"
        List<String> nonTemplateBranchNames = allBranchNames - templateBranchName
        //println "DEBUG: nonTemplateBranchNames = $nonTemplateBranchNames"
        List<ConcreteJob> expectedJobs = this.expectedJobs(templateJobs, nonTemplateBranchNames)

        createMissingJobs(expectedJobs, currentTemplateDrivenJobNames, templateJobs)
        if (!noDelete) {
            deleteDeprecatedJobs(currentTemplateDrivenJobNames - expectedJobs.jobName)
        }
    }

    public void createMissingJobs(List<ConcreteJob> expectedJobs, List<String> currentJobs, List<TemplateJob> templateJobs) {
        //println "DEBUG: createMissingJobs"
        //println "DEBUG: currentJobs = $currentJobs"
        //println "DEBUG: expectedJobs = $expectedJobs"
        //println "DEBUG: templateJobs = $templateJobs"

        
        List<ConcreteJob> missingJobs = expectedJobs.findAll { !currentJobs.contains(it.jobName) }
        //println "DEBUG: missingJobs = $missingJobs"
        if (!missingJobs) return

        for(ConcreteJob missingJob in missingJobs) {
            println "Creating missing job: ${missingJob.jobName} from ${missingJob.templateJob.jobName}"
            jenkinsApi.cloneJobForBranch(missingJob, templateJobs)
            if (startOnCreate) {
                jenkinsApi.startJob(missingJob)
            }
        }

    }

    public void deleteDeprecatedJobs(List<String> deprecatedJobNames) {
        //println "DEBUG: deleteDeprecatedJobs"
        if (!deprecatedJobNames) return
        println "Deleting deprecated jobs:\n\t${deprecatedJobNames.join('\n\t')}"
        deprecatedJobNames.each { String jobName ->
            jenkinsApi.deleteJob(jobName)
        }
    }

    public List<ConcreteJob> expectedJobs(List<TemplateJob> templateJobs, List<String> branchNames) {
        //println "DEBUG: expectedJobs"
        //println "templateJobs = $templateJobs"
        //println "branchNames = $branchNames"

        branchNames.collect { String branchName ->
            templateJobs.collect { TemplateJob templateJob -> templateJob.concreteJobForBranch(branchName) }
        }.flatten()
    }

    public List<String> templateDrivenJobNames(List<TemplateJob> templateJobs, List<String> allJobNames) {
        //println "DEBUG: templateDrivenJobNames"
        List<String> templateJobNames = templateJobs.jobName
        List<String> templateBaseJobNames = templateJobs.baseJobName

        // don't want actual template jobs, just the jobs that were created from the templates
        return (allJobNames - templateJobNames).findAll { String jobName ->
            templateBaseJobNames.find { String baseJobName -> jobName.startsWith(baseJobName)}
        }
    }


    List<TemplateJob> findRequiredTemplateJobs(List<String> allJobNames) {
        //println "DEBUG: findRequiredTemplateJobs"
        //String regex = /^($templateJobPrefix-[^-]*)-($templateBranchName)$/
        // template_v2_GIT_REPO_NAME_develop-Deploy
        // sensbot_develop_Deploy
        String regex = /^template_v2_GIT_REPO_NAME-($templateBranchName)-([^-]+)$/

        //println "templateJobPrefix = $templateJobPrefix"
        //println "templateBranchName = $templateBranchName"

        List<TemplateJob> templateJobs = allJobNames.findResults { String jobName ->
            TemplateJob templateJob = null
            //jobName.find(regex) { full, baseJobName, branchName ->
                jobName.find(regex) { full, branchName, onlyJobName ->
                String baseJobName = "${templateJobPrefix}_${onlyJobName}"
                //println "full = $full"
                //println "branchName = $branchName"
                //println "baseJobName = $baseJobName"
                templateJob = new TemplateJob(jobName: full, baseJobName: baseJobName, templateBranchName: branchName)
            }
            return templateJob
        }

        assert templateJobs?.size() > 0, "Unable to find any jobs matching template regex: $regex\nYou need at least one job to match the templateJobPrefix and templateBranchName suffix arguments"
        return templateJobs
    }
    
    public void syncViews(List<String> allBranchNames) {
        //println "DEBUG: syncViews"
        List<String> existingViewNames = jenkinsApi.getViewNames(this.nestedView)
        List<BranchView> expectedBranchViews = allBranchNames.collect { String branchName -> new BranchView(branchName: branchName, templateJobPrefix: this.templateJobPrefix) }

        List<BranchView> missingBranchViews = expectedBranchViews.findAll { BranchView branchView -> !existingViewNames.contains(branchView.viewName)}
        addMissingViews(missingBranchViews)

        if (!noDelete) {
            List<String> deprecatedViewNames = getDeprecatedViewNames(existingViewNames, expectedBranchViews)
            deleteDeprecatedViews(deprecatedViewNames)
        }
    }

    public void addMissingViews(List<BranchView> missingViews) {
        //println "Missing views: $missingViews"
        for (BranchView missingView in missingViews) {
            jenkinsApi.createViewForBranch(missingView, this.nestedView, this.viewRegex)
        }
    }

    public List<String> getDeprecatedViewNames(List<String> existingViewNames, List<BranchView> expectedBranchViews) {
        //println "DEBUG: getDeprecatedViewNames"
         return existingViewNames?.findAll { it.startsWith(this.templateJobPrefix) } - expectedBranchViews?.viewName ?: []
    }

    public void deleteDeprecatedViews(List<String> deprecatedViewNames) {
        //println "Deprecated views: $deprecatedViewNames"

        for(String deprecatedViewName in deprecatedViewNames) {
            jenkinsApi.deleteView(deprecatedViewName, this.nestedView)
        }

    }

    JenkinsApi initJenkinsApi() {
        //println "DEBUG: initJenkinsApi"
        if (!jenkinsApi) {
            assert jenkinsUrl != null
            if (dryRun) {
                println "DRY RUN! Not executing any POST commands to Jenkins, only GET commands"
                this.jenkinsApi = new JenkinsApiReadOnly(jenkinsServerUrl: jenkinsUrl)
            } else {
                this.jenkinsApi = new JenkinsApi(jenkinsServerUrl: jenkinsUrl)
            }

            if (jenkinsUser || jenkinsPassword) this.jenkinsApi.addBasicAuth(jenkinsUser, jenkinsPassword)
        }

        return this.jenkinsApi
    }

    GitApi initGitApi() {
        //println "DEBUG: initGitApi"
        if (!gitApi) {
            assert gitUrl != null
            this.gitApi = new GitApi(gitUrl: gitUrl)
            if (this.branchNameRegex){
                this.gitApi.branchNameFilter = ~this.branchNameRegex
            }
        }

        return this.gitApi
    }
}
