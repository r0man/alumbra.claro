(defproject alumbra/claro "0.1.0-SNAPSHOT"
  :description "An alumbra GraphQL executor on top of Claro."
  :url "https://github.com/alumbra/alumbra.claro"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"
            :year 2016
            :key "mit"}
  :dependencies [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
                 [alumbra/spec "0.1.0-SNAPSHOT"]
                 [camel-snake-kebab "0.4.0"]
                 [claro "0.2.4-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[alumbra/validator "0.1.0-SNAPSHOT"]
                                  [alumbra/parser "0.1.0-SNAPSHOT"]]}}
  :pedantic? :abort)
