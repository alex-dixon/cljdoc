(ns cljdoc.analysis.task
  {:boot/export-tasks true}
  (:require [boot.core :as b]
            [boot.pod :as pod]
            [clojure.java.io :as io]
            [clojure.edn]
            [clojure.string]
            [cljdoc.util]
            [cljdoc.util.boot]
            [cljdoc.spec])
  (:import (java.net URI)
           (java.nio.file Files)))

(def sandbox-analysis-deps
  "This is what is being loaded in the pod that is used for analysis.
  It is also the stuff that we cannot generate documentation for in versions
  other than the ones listed below. (See CONTRIBUTING for details.)"
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/java.classpath "0.2.2"]
    [org.clojure/tools.namespace "0.2.11"]
    [org.clojure/clojurescript "1.10.238"] ; Codox depends on old CLJS which fails with CLJ 1.9
    [org.clojure/core.async "RELEASE"] ; Manifold dev-dependency — we should probably detect+load these
    [org.clojure/tools.logging "RELEASE"] ; Pedestal Interceptors dev-dependency — we should probably detect+load these
    [codox "0.10.3" :exclusions [enlive hiccup org.pegdown/pegdown]]])

(defn copy [uri file]
  (with-open [in  (io/input-stream uri)
              out (io/output-stream file)]
    (io/copy in out)))

(defn copy-jar-contents-impl
  [jar-uri target-dir]
  (let [remote-jar? (boolean (.getHost jar-uri))  ; basic check if jar is at remote location
        jar-local (if remote-jar?
                    (let [jar-f (io/file target-dir "downloaded.jar")]
                      (io/make-parents jar-f)
                      (printf "Downloading remote jar...\n")
                      (copy jar-uri jar-f)
                      (.getPath jar-f))
                    (str jar-uri))]
    (printf "Unpacking %s\n" jar-local)
    (pod/unpack-jar jar-local target-dir)
    ;; Some projects include their `out` directories in their jars,
    ;; usually somewhere under public/, this tries to clear those.
    ;; NOTE this means projects with the group-id `public` will fail to build.
    (when (.exists (io/file target-dir "public"))
      (println "Deleting public/ dir")
      (cljdoc.util/delete-directory! (io/file target-dir "public")))
    (when (.exists (io/file target-dir "deps.cljs"))
      (println "Deleting deps.cljs")
      (.delete (io/file target-dir "deps.cljs")))
    (when remote-jar? (.delete (io/file jar-local)))))

(b/deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file, may be a URI or a path on local disk."]
  (b/with-pre-wrap fileset
    (let [d (b/tmp-dir!)]
      (copy-jar-contents-impl (URI. jar) (io/file d "jar-contents/"))
      (-> fileset (b/add-resource d) b/commit!))))

(defn analyze-impl
  [project version jar pom]
  {:pre [(symbol? project) (seq version) (seq jar) (seq pom)]}
  (let [tmp-dir      (cljdoc.util/system-temp-dir (str "cljdoc-" project "-" version))
        jar-contents (io/file tmp-dir "jar-contents/")
        grimoire-pod (pod/make-pod {:dependencies (conj sandbox-analysis-deps [project version])
                                    :directories #{"src"}})
        platforms    (get-in cljdoc.util/hardcoded-config
                             [(cljdoc.util/normalize-project project) :cljdoc.api/platforms]
                             (cljdoc.util/infer-platforms-from-src-dir jar-contents))
        namespaces   (get-in cljdoc.util/hardcoded-config
                             [(cljdoc.util/normalize-project project) :cljdoc.api/namespaces])
        build-cdx      (fn build-cdx [jar-contents-path platf]
                         (println "Analyzing" project platf)
                         (pod/with-eval-in grimoire-pod
                           (require 'cljdoc.analysis.impl)
                           (cljdoc.analysis.impl/codox-namespaces
                            (quote ~namespaces) ; the unquote seems to be recursive in some sense
                            ~jar-contents-path
                            ~platf)))]

    (copy-jar-contents-impl (URI. jar) jar-contents)

    (let [cdx-namespaces (->> (map #(build-cdx (.getPath jar-contents) %) platforms)
                              (zipmap platforms))
          ana-result     {:group-id (cljdoc.util/group-id project)
                          :artifact-id (cljdoc.util/artifact-id project)
                          :version version
                          :codox cdx-namespaces
                          :pom-str (slurp pom)}]
      (cljdoc.spec/assert :cljdoc/cljdoc-edn ana-result)
      (cljdoc.util/delete-directory! jar-contents)
      (doto (io/file tmp-dir (cljdoc.util/cljdoc-edn project version))
        (io/make-parents)
        (spit (pr-str ana-result))))))

(b/deftask analyze
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   j jarpath JARPATH str "Absolute path to the jar (can be local/remote)"
   _ pompath POMPATH str "Absolute path to the pom (can be local/remote)"]
  (assert project "project is required")
  (assert version "version is required")
  (assert jarpath "jarpath is required")
  (assert pompath "jarpath is required")
  (b/with-pre-wrap fs
    (let [tempd           (b/tmp-dir!)
          cljdoc-edn-file (analyze-impl project version jarpath pompath)]
      (io/copy cljdoc-edn-file
               (doto (io/file tempd (cljdoc.util/cljdoc-edn project version))
                 (io/make-parents)))
      (-> fs (b/add-resource tempd) b/commit!))))
