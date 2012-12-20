(ns gavagai.core
  (:require [clojure.string :as str]
            [lazymap.core :as lz])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]
           [java.util.regex Pattern]))

(defrecord Translator [registry])

(defprotocol Translatable
  "Protocol to translate generic Java objects"
  (translate* [obj translator opts]))

(defn method->arg
  [^Method method]
  (let [name (.getName method)
        typ (.getReturnType method)
        conv (str/replace name #"^get" "")
        norm (str/lower-case (str/replace conv #"(\p{Lower})(\p{Upper})" "$1-$2"))
        full (if (= (.getName typ) "boolean") (-> norm (str "?") (str/replace #"^is-" "")) norm)]
    (keyword full)))

(defn get-read-methods
  [klass]
  (reduce (fn [acc ^java.beans.PropertyDescriptor pd]
            (let [method (.getReadMethod pd)]
              (if (and method
                       (zero? (alength (.getParameterTypes method))))
                (conj acc method)
                acc)))
          #{}
          (seq (.. Introspector
                   (getBeanInfo klass)
                   (getPropertyDescriptors)))))

(defn inspect-class
  "Inspects a class and returns a seq of methods name to their gavagai keyword representation"
  [klass]
  (into {}
        (map (fn [m]
               [(.getName m) (method->arg m)])
             (get-read-methods klass))))

(defn safe-inc
  [n]
  (if n
    (inc n)
    1))

(defn translate-with
  [translator obj {:keys [depth max-depth] :as opts}]
  (let [klass (.getClass obj)]
    (when-let [converter (get-in translator [:registry klass])]
      (converter
       translator
       obj
       (if max-depth
         (assoc opts :depth (safe-inc depth))
         opts)))))

(defn translate
  "Recursively translates a Java object to Clojure data structures.
   Arguments
     :max-depth (integer)      -> for recursive graph objects, to avoid infinite loops
     :lazy? (bool)             -> overrides the param given in the spec
                                  (true by default)
     :translate-arrays? (bool) -> translate native arrays to vectors
                                  (false by default)"
  ([translator obj {:keys [max-depth depth translate-arrays?] :as opts}]
     (if (and max-depth (>= depth max-depth))
       obj
       (try
         (let [conv (translate-with translator obj opts)]
           (cond
            conv conv
            (and translate-arrays? (instance? any-array-class obj)) (translate-list translator obj opts)
            :else (translate* obj translator opts)))
         (catch Exception e
           (throw e))))))

(defn type-array-of
  [t]
  (.getClass (java.lang.reflect.Array/newInstance t 0)))

(def any-array-class (type-array-of Object))

(extend-type nil
  Translatable
  (translate* [obj _ _] obj))

(extend-type java.util.Map
  Translatable
  (translate* [obj translator {:keys [depth max-depth] :as opts}]
    (persistent!
     (reduce
      (fn [acc ^java.util.Map$Entry e]
        (assoc! acc
                (translate
                 translator
                 (.getKey e)
                 (if max-depth
                   (assoc opts :depth (safe-inc depth))
                   opts))
                (translate
                 translator
                 (.getValue e)
                 (if max-depth
                   (assoc opts :depth (safe-inc depth))
                   opts))))
      (transient {}) obj))))

(defn translate-list
  [translator obj {:keys [depth max-depth] :as opts}]
  (persistent!
   (reduce
    (fn [acc obj]
      (conj! acc (translate
                  translator
                  obj
                  (if max-depth
                    (assoc opts
                      :depth (safe-inc depth))
                    opts))))
    (transient []) obj)))

(extend-type java.util.List
  Translatable
  (translate* [obj translator opts] (translate-list translator obj opts)))

(extend-type Object
  Translatable
  (translate* [obj _ _]
    obj))

(defn get-var-in-ns
  [nspace symb]
  (var-get (ns-resolve nspace symb)))

(def empty-array (object-array 0))

(defn invoke-method
  [^Method m obj]
  (.invoke m obj empty-array))

(defn- convert-to-pattern
  [elt]
  (if (instance? Pattern elt)
    elt
    (Pattern/compile (Pattern/quote (name elt)))))

(defn make-converter
  [class-name & [{:keys [only exclude add lazy? translate-arrays?]
                  :or {exclude [] add {} lazy? true} :as opts}]]
  (let [klass (Class/forName class-name)
        klass-symb (symbol class-name)
        read-methods (get-read-methods klass)
        conf {:translate-arrays? translate-arrays?}
        full-exclude (map convert-to-pattern exclude)
        full-only (map convert-to-pattern only)
        fields-nb (if only
                    (+ (count only) (count add))
                    (- (+ (count read-methods) (count add)) (count exclude)))
        hash-fn (if (> fields-nb 8) hash-map array-map)
        mets (reduce (fn [acc ^Method m]
                       (let [m-name (.getName m)
                             k-name (keyword (method->arg m))]
                         (cond
                          (some #(re-matches % (name k-name)) full-exclude) acc
                          (and only (some #(re-matches % (name k-name)) full-only))
                          (assoc acc k-name (partial invoke-method m))
                          (not (empty? only)) acc
                          :else (assoc acc k-name (partial invoke-method m)))))
                     {} read-methods)
        ;; obj (with-meta (gensym "obj") {:tag klass-symb})
        ]
    (fn
      [translator obj opts]
      (let [lazy-over? (get opts :lazy? lazy?)
            map-fn (if lazy-over?
                     lz/lazy-hash-map*
                     hash-fn)
            return (apply
                    map-fn
                    (concat
                     (mapcat (fn [[kw getter]]
                               (list kw (translate translator (getter obj) (merge opts conf)))) mets)
                     (mapcat (fn [[kw f]]
                               (list kw (f obj))) add)))]
        return))))

(defn default-map
  [base default]
  (merge-with
   (fn [b d]
     (if (nil? b) d b))
   base default))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn make-translator
  []
  (Translator. {}))

(defn register-converter
  [translator [class-name opts]]
  (let [klass (Class/forName class-name)
        converter (make-converter class-name opts)
        full-converter (vary-meta converter assoc :gavagai-spec opts)]
    (update-in translator [:registry] assoc klass full-converter)))

(defn unregister-converter
  [translator class-name]
  (let [klass (Class/forName class-name)]
    (dissoc-in translator [:registry klass])))

(comment
  (defmacro register-converters
   "Registers a converter for a given Java class given as a String. Takes an optional map
 as a first argument defining default options. Individual option maps are merged with defaults.
   Optional arguments
     - :only    (vector)  -> only translate these methods
     - :exclude (vector)  -> exclude the methods from translation (keywords or patterns)
     - :add     (hash)    -> add under the given key the result of applying
                             the function given as val to the object
     - :lazy?   (bool)    -> should the returned map be lazy or not
                             (lazy by default)
     - :translate-arrays? (bool) -> translate native arrays to vectors
                                    (false by default)
   Example
     (register-converters
       [\"java.util.Date\" :exclude [:class] :add {:string str} :lazy? false])

   is equivalebnt to:
    (register-converters
       {:exclude [:class] :lazy? false}
       [\"java.util.Date\" :add {:string str}])"
   [default & conv-defs]
   (let [[full-conv-defs default-opts] (if (map? default)
                                         [conv-defs default]
                                         [(conj conv-defs default) {}])
         added (count full-conv-defs)]
     `(do
        (init-translator!)
        ~@(for [[class-name & {:as opt-spec}] full-conv-defs
                :let [given-opts (default-map opt-spec
                                   {:exclude [] :add {} :lazy? true})
                      full-opts (merge-with
                                 (fn [default over]
                                   (cond
                                    (every? map? [default over]) (merge default over)
                                    (every? coll? [default over]) (distinct (concat default over))
                                    :else over))
                                 default-opts given-opts)]]
            `(let [conv# (make-converter ~class-name ~full-opts)]
               (extend ~(symbol class-name)
                 ~'Clojurable
                 {:translate-object conv#})))
        ~added))))

(comment
  (register-converters
   ["java.util.Date" :exclude [:class] :add {:string str}])
  (with-translator-ns gavagai.core (translate (java.util.Date.))))