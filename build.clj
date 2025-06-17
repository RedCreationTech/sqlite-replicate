(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.edn :as edn])) ; Added for reading deps.edn

;; --- Build Configuration ---
;; These values are read from deps.edn's :pom-data by b/pom-data
;; but we might need them for constructing paths or for other tasks.
;; It's often good practice to define them or read them programmatically if needed.
;; For b/write-pom, it will automatically use :pom-data from deps.edn if basis is provided.

;; Helper function to read :pom-data from deps.edn
(defn- get-pom-data-from-deps []
  (let [deps-map (edn/read-string (slurp "deps.edn"))]
    (:pom-data deps-map)))

(def pom-data-val (get-pom-data-from-deps))
(def lib (or (-> pom-data-val :lib symbol) 'group/artifact)) ; Read lib from pom-data, ensure it's a symbol
(def version (or (-> pom-data-val :version) "0.0.0-UNKNOWN"))    ; Read version from pom-data

(def build-dir "target")
(def class-dir (str build-dir "/classes"))
(def basis (b/create-basis {:project "deps.edn"})) ; Create basis from project deps

;; We want pom.xml in the project root for deps-deploy, or build-dir for packaging.
;; tools.build's b/write-pom default is target/classes/META-INF/maven/GROUP/ARTIFACT/pom.xml
;; Let's target project root for pom.xml to be conventional for deps-deploy.
(def project-pom-file (io/file "pom.xml"))
(def jar-file (format "%s/%s-%s.jar" build-dir (name lib) version))


;; --- Build Tasks ---

(defn clean "Remove the build target directory." [_]
  (println (str "Cleaning " build-dir "..."))
  (b/delete {:path build-dir}))

(defn pom "Generate pom.xml." [params]
  (println (str "Writing pom.xml to " project-pom-file "..."))
  (b/write-pom {:class-dir class-dir ; b/write-pom needs a class-dir to write pom.properties
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src"]
                ;; Pass the full :pom-data map from deps.edn.
                ;; b/write-pom takes :pom-data as a key, expecting the map from deps.edn's :pom-data.
                :pom-data pom-data-val
                ;; :target-dir build-dir ; Not needed if :pom-file specifies full path
                :pom-file project-pom-file ; Explicitly set pom file to be in project root
                })
  (println "pom.xml generated." (str "at " project-pom-file)))


(defn jar "Build the library JAR file." [params]
  (clean nil)      ; Clean previous build
  (pom params)     ; Generate pom.xml first, params might override pom-file for jar's internal pom
                   ; but for the main pom, we use project-pom-file.

  (println (str "Building JAR: " jar-file "..."))
  (b/copy-dir {:src-dirs ["src"]         ; Copy source files
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))
  (println "JAR built successfully." (str "at " jar-file)))

;; Placeholder for a uberjar task if ever needed
;; (defn uber "Build an uberjar." [_] ...)

;; Note: Deployment via deps-deploy is typically done as a separate step
;; after 'jar' task, using the generated pom.xml and jar.
;; If you want to integrate deployment here, you'd call deps-deploy,
;; possibly using b/process if you want to run it as a shell command,
;; or by adding deps-deploy as a library dependency to tools.build itself
;; and calling its functions programmatically.
;; For now, keeping deployment separate via `clojure -M:deploy` is simpler.

;; A simple way to list tasks, could be more sophisticated.
(defn tasks-help [_]
 (println "Available build tasks (invoke with clojure -T:build <task>):")
 (println "  clean   - Remove the build target directory.")
 (println "  pom     - Generate pom.xml from deps.edn's :pom-data.")
 (println "  jar     - Build the library JAR file (cleans and generates pom.xml first)."))

;; Default task if none specified (optional)
(defn -main [& args]
  (tasks-help nil))

(println "Build script loaded. Run 'clojure -T:build tasks-help' for available tasks.")
