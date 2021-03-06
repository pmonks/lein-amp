;
; Copyright © 2014 Peter Monks (pmonks@gmail.com)
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the Eclipse Public License v1.0
; which accompanies this distribution, and is available at
; http://www.eclipse.org/legal/epl-v10.html
;
; Contributors:
;    Carlo Sciolla - initial implementation

(ns leiningen.amp.install
  (:require [clojure.java.io             :as io]
            [cemerick.pomegranate.aether :as aether]
            [leiningen.amp.package       :as package]
            [leiningen.core.main         :as main])
  (:import [org.alfresco.repo.module.tool ModuleManagementTool]))

(defn- locate-amp
  "Finds the AMP file produced by the given project"
  [{:keys [target-path] :as project}]
  (if-let [amp (package/target-file project (io/file target-path))]
    amp
    (main/abort "The AMP file was not found. Did you remember to run `lein amp package` first?")))

(defn- repo-map
  "Builds a Pomegranate compatible map of repositories given the project configured ones.
   The repositories are found in the project to be a sequence of something like:
       [<id> {:url \"http://url.to.repo\" ...}]"
  [repos]
  (let [result
        (reduce (fn [m [id {:keys [url]}]]
                  (assoc m id url)) {} repos)]
    result))

(defn- depname
  "Stringifies the filename of a leiningen dependency"
  [dep]
  (-> dep first name))

(defn- version
  "Stringifies the version of a leiningen dependency"
  [dep]
  (str "-" (second dep)))

(defn- classifier
  "Provides an appendable string for the classifier"
  [dep]
  (if-let [classifier (:classifier dep)]
    (str "-" classifier)
    ""))

(defn- extension
  "Provides an appenavle string for the extension"
  [dep]
  (if-let [ext (:extension dep)]
    (str "." extension)
    ".war"))

(defn- dep-to-filename
  "Translates a dependency in a filename"
  [dep]
  (str (depname dep) (version dep) (classifier dep) (extension dep)))

(defn- dep-filter
  "Creates a function suitable to `filter` a seq of dependencies to find the requested one"
  [dep]
  (fn [^java.io.File file]
    (let [name (.getName file)
          dep-name (dep-to-filename dep)]
      (= dep-name name))))

(defn- find-one
  "Finds one and only one dependency that matches the given file"
  [file files]
  (let [filtered (filter (dep-filter file) files)
        file (first filtered)]
    (if file
      file
      (main/abort "The target WAR couldn't be found in the configured repository"))))

(defn- find-dependency
  "Finds the WAR from the project then returns its file"
  [{:keys [repositories amp-target-war]}]
  (try
    (let [files (aether/dependency-files
                 (aether/resolve-dependencies :repositories (repo-map repositories)
                                              :coordinates  [amp-target-war]))]
      (if (empty? files)
        (main/abort "No target WAR was found. Did you set :amp-target-war in your project.clj?")
        (find-one amp-target-war files)))
    (catch org.sonatype.aether.transfer.ArtifactNotFoundException e
      (when main/*debug* (.printStackTrace e))
      (main/abort "The target WAR specified in your project could not be found in the configured repositories"))
    (catch Exception e
      (when main/*debug* (.printStackTrace e))
      (main/abort "Invalid target WAR found in project:" amp-target-war))))

(defn- reference-war
  "Creates and validates a path to a target WAR file"
  [loc]
  (let [file (io/file loc)]
    (if (not (.exists ^java.io.File file))
      (main/abort "The specified WAR file does not exist:" loc)
      file)))

(defn- dependency-war
  "Creates and validates a target WAR file specified as a project dependency"
  [{:keys [amp-target-war] :as project}]
  (if (not amp-target-war)
    (main/abort "You need to specify a target WAR location"))
  (let [dependency (find-dependency project)
        temp-war (io/file (:target-path project) (dep-to-filename amp-target-war))]
    (if (not (.exists ^java.io.File temp-war))
      (io/copy dependency temp-war))
    temp-war))

(defn- get-war-str
  "Finds the string representation of the target WAR location, if one is given. If
   the location is specified at the command line it takes precedence over the project
   configuration."
  [target-war args]
  (or (first args)
      (if (string?  target-war)
        target-war
        false)))

(defn locate-war
  "Locates the WAR file to deploy to. The WAR can be specified as the argument,
   or as a dependency in the project at the entry :amp-target-war"
  [{:keys [amp-target-war] :as project} args]
  (if-let [target-war (get-war-str amp-target-war args)]
    (reference-war target-war)
    (dependency-war project)))

(defn- install!
  "Uses the Alfresco MMT to install the AMP into the target WAR"
  [amp war]
  (ModuleManagementTool/main (into-array String ["install" (str amp) (str war)]))
  (println "AMP successfully installed into" war))

(defn install-amp!
  "Installs the generated AMP into the specified WAR. If the target WAR is not
   specified as an argument, it is retrieved from the project at the
   :amp-target-war"
  [project args]
  (package/package-amp! project args)
  (let [amp (locate-amp project)
        war (locate-war project args)]
    (install! amp war)))
