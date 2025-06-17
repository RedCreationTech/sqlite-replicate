(ns build
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]))

;; Helper function to read :pom-data from deps.edn
(defn- get-pom-data-from-deps []
  (let [deps-map (edn/read-string (slurp "deps.edn"))]
    (:pom-data deps-map)))

(def pom-data-val (get-pom-data-from-deps))
(def lib (or (-> pom-data-val :lib symbol) 'group/artifact)) ; Read lib from pom-data, ensure it's a symbol
(def version (or (-> pom-data-val :version) "0.0.0-UNKNOWN"))    ; Read version from pom-data

(def build-dir "target")
(def jar-file (format "%s/%s-%s.jar" build-dir (name lib) version))

;; --- Build Tasks ---

(defn clean "Remove the build target directory." [_]
  (println (str "Cleaning " build-dir "..."))
  (let [target-dir (io/file build-dir)]
    (when (.exists target-dir)
      (doseq [file (reverse (file-seq target-dir))]
        (io/delete-file file true)))
    (println "Clean completed.")))

(defn pom "Generate pom.xml using clojure." [_]
  (println "Generating pom.xml...")
  (let [result (shell/sh "clojure" "-Spom")]
    (if (zero? (:exit result))
      (println "pom.xml generated successfully.")
      (println "Error generating pom.xml:" (:err result)))))

(defn jar "Build the library JAR file using shell commands." [_]
  (clean nil)
  (pom nil)
  (println (str "Building JAR: " jar-file "..."))
  
  ;; Create target directory if it doesn't exist
  (.mkdirs (io/file build-dir))
  
  ;; Copy source files to target/classes
  (let [classes-dir (str build-dir "/classes")]
    (.mkdirs (io/file classes-dir))
    
    ;; Copy src directory to classes
    (let [copy-result (shell/sh "cp" "-r" "src" classes-dir)]
      (if (zero? (:exit copy-result))
        (println "Source files copied.")
        (println "Error copying source files:" (:err copy-result))))
    
    ;; Create JAR file
    (let [jar-result (shell/sh "jar" "cf" jar-file "-C" classes-dir ".")]
      (if (zero? (:exit jar-result))
        (println "JAR built successfully at" jar-file)
        (println "Error building JAR:" (:err jar-result))))))

;; A simple way to list tasks
(defn tasks-help [_]
 (println "Available build tasks (invoke with clojure -T:build <task>):")
 (println "  clean   - Remove the build target directory.")
 (println "  pom     - Generate pom.xml from deps.edn.")
 (println "  jar     - Build the library JAR file (cleans and generates pom.xml first)."))

;; Default task if none specified
(defn -main [& args]
  (tasks-help nil))

(println "Build script loaded. Run 'clojure -T:build tasks-help' for available tasks.")
