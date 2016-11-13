(ns alumbra.claro.introspection
  (:require [claro.data :as data]
            [claro.engine :as engine]
            [claro.projection :as projection]
            [clojure.string :as string]))

;; TODO:
;; There is a bit of a performance gain to be had if we generate the maps for
;; the single types beforehand and just to a simple lookup within the 'Type'
;; resolvable.

(declare ->Directive ->EnumValues ->Fields ->Type as-nested-type)

(defmacro ^:private defrecord-
  [type & body]
  `(do
     (defrecord ~type ~@body)
     (alter-meta! (var ~(symbol (str "->" type))) assoc :private true)
     (alter-meta! (var ~(symbol (str "map->" type))) assoc :private true)))

;; ## Schema

(defn- build-schema-record
  [{:keys [schema-root type->kind directives]}]
  {:types            (vec
                       (keep
                         (fn [[type-name kind]]
                           (when (not= kind :directive)
                             (->Type type-name)))
                         type->kind))
   :directives       (mapv #(apply ->Directive %) directives)
   :queryType        (->Type (get schema-root "query"))
   :mutationType     (some-> schema-root (get "mutation") ->Type)
   :subscriptionType (some-> schema-root (get "subscription") ->Type)})

(defrecord Schema []
  data/Resolvable
  (resolve! [_ {:keys [alumbra/analyzed-schema]}]
    (build-schema-record analyzed-schema)))

;; ## Types

;; ### Base Map

(defn- make-type-map
  [values]
  (merge
    {:name          nil
     :description   nil
     :fields        (->Fields [] nil)
     :interfaces    nil
     :possibleTypes nil
     :enumValues    (->EnumValues [] nil)
     :inputFields   nil
     :ofType        nil}
    values))

;; ### Nested Types (Non-Null and List)

(defn- as-nested-type
  [nested-type-description]
  (let [{:keys [type-description non-null? type-name]}
        nested-type-description]
    (cond non-null?
          (make-type-map
            {:kind   :NON_NULL
             :ofType (as-nested-type
                       (dissoc nested-type-description :non-null?))})

          type-description
          (make-type-map
            {:kind   :LIST
             :ofType (as-nested-type type-description)})

          :else
          (->Type type-name))))

;; ### Arguments

(defn- as-argument
  [argument]
  (let [{:keys [argument-name type-description]} argument]
    {:name         argument-name
     :description  nil
     :type         (as-nested-type type-description)
     ;; FIXME: default value for arguments
     :defaultValue nil}))

(defn- as-arguments
  [arguments]
  (mapv as-argument arguments))

;; ### Fields

(defn- as-field
  [field]
  (let[{:keys [field-name type-description arguments]} field]
    {:name              field-name
     :description       nil
     :args              (as-arguments (vals arguments))
     :type              (as-nested-type type-description)
     :isDeprecated      false
     :deprecationReason nil}))

(defrecord- Fields [fields includeDeprecated]
  data/Resolvable
  (resolve! [_ _]
    (when (seq fields)
      (vec
        (keep
          (fn [{:keys [field-name] :as field}]
            (when (not= field-name "__typename")
              (as-field field)))
          fields)))))

;; ### Object Types

(defn- as-object-type
  [{:keys [alumbra/analyzed-schema]} name]
  (let [{:keys [fields implements]}
        (get-in analyzed-schema [:types name])]
    (make-type-map
      {:name       name
       :kind       :OBJECT
       :fields     (->Fields (vals fields) nil)
       :interfaces (mapv ->Type implements)})))

;; ### Interface Types

(defn as-interface-type
  [{:keys [alumbra/analyzed-schema]} name]
  (let [{:keys [fields implemented-by]}
        (get-in analyzed-schema [:interfaces name])]
    (make-type-map
      {:name          name
       :kind          :INTERFACE
       :fields        (->Fields (vals fields) nil)
       :possibleTypes (mapv ->Type implemented-by)})))

;; ### Union Types

(defn- as-union-type
  [{:keys [alumbra/analyzed-schema]} name]
  (let [{:keys [union-types]}
        (get-in analyzed-schema [:unions name])]
    (make-type-map
      {:name          name
       :kind          :UNION
       :possibleTypes (mapv ->Type union-types)})))

;; ### Scalar Types

(defn- as-scalar-type
  [_ name]
  (make-type-map
    {:name name
     :kind :SCALAR}))

;; ### Input Types

(defn- as-input-type
  [{:keys [alumbra/analyzed-schema]} name]
  (let [{:keys [fields]}
        (get-in analyzed-schema [:input-types name])]
    (make-type-map
      {:name          name
       :kind          :INPUT_OBJECT
       :fields        (->Fields (vals fields) nil)})))

;; ### Enum Types

(defrecord- EnumValues [enum-values includeDeprecated]
  data/Resolvable
  (resolve! [_ _]
    (when (seq enum-values)
      (mapv
        (fn [v]
          {:name              v
           :description       nil
           :isDeprecated      false
           :deprecationReason nil})
        enum-values))))

(defn- as-enum-type
  [{:keys [alumbra/analyzed-schema]} name]
  (let [enum-values (get-in analyzed-schema [:enums name])]
    (make-type-map
      {:name          name
       :kind          :ENUM
       :enumValues    (->EnumValues enum-values nil)})))

;; ### Dispatch Resolvable

(defn dispatch-type-record
  [{:keys [alumbra/analyzed-schema] :as env} type-name]
  (let [{:keys [type->kind]} analyzed-schema]
    (case (type->kind type-name)
      :type       (as-object-type env type-name)
      :interface  (as-interface-type env type-name)
      :union      (as-union-type env type-name)
      :scalar     (as-scalar-type env type-name)
      :enum       (as-enum-type env type-name)
      :input-type (as-input-type env type-name))))

(defrecord Type [name]
  data/Resolvable
  (resolve! [_ env]
    (dispatch-type-record env name)))

;; ## Directives

(defrecord- Directive [name directive]
  data/Resolvable
  (resolve! [_ _]
    (let [{:keys [directive-locations arguments]} directive]
      {:name        name
       :description nil
       :locations   (mapv
                      (fn [location]
                        (-> (clojure.core/name location)
                            (string/replace "-" "_")
                            (string/upper-case)))
                      directive-locations)
       :args        (as-arguments (vals arguments))})))

;; ## Middleware

(defn- strip-introspection-fields
  [{:keys [schema-root] :as schema}]
  (if-let [root-type (get schema-root "query")]
    (update-in schema [:types root-type :fields] dissoc "__schema" "__type")
    schema))

(defn wrap-introspection
  "Wrap the given engine to allow usage of [[->Schema]] and [[->Type]]
   resolvables for GraphQL schema introspection.

   `analyzed-schema` has to conform to `:alumbra/analyzed-schema`."
  [engine analyzed-schema]
  (let [schema (strip-introspection-fields analyzed-schema)]
    (->> (fn [resolver]
           (fn [env batch]
             (resolver
               (assoc env :alumbra/analyzed-schema schema)
               batch)))
         (engine/wrap engine))))
