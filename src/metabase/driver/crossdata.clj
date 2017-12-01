(ns metabase.driver.crossdata
  ;; TODO - rework this to be like newer-style namespaces that use `u/drop-first-arg`
  (:require [clojure.java.jdbc :as jdbc]
            (clojure [set :refer [rename-keys], :as set]
                     [string :as s])
            [clojure.tools.logging :as log]
            [honeysql.core :as hsql]
            [metabase.db.spec :as dbspec]
            [metabase.driver :as driver]
            [metabase.driver.generic-sql :as sql]
            [metabase.util :as u]
            [metabase.driver.generic-sql.query-processor :as qprocessor]
            [metabase.query-processor.util :as qputil]
            [metabase.models.field :as fieldd]
            (honeysql [core :as hsql]
                      [format :as hformat]
                      [helpers :as h]
                      types)
            [metabase.driver.generic-sql.util.unprepare :as unprepare]
            [metabase.util.honeysql-extensions :as hx])
  (:import java.util.UUID
           (java.util Collections Date)
           (metabase.query_processor.interface DateTimeValue Value)
           (metabase.query_processor.interface DateTimeValue)))




(def ^:private ^:const column->base-type
  "Map of Crossdata column types -> Field base types.
   Add more mappings here as you come across them."
  {
   :SQL_DECIMAL             :type/Decimal
   :SQL_DOUBLE              :type/Decimal
   :SQL_FLOAT               :type/Float
   :SQL_INTEGER             :type/Integer
   :SQL_REAL                :type/Decimal
   :SQL_VARCHAR             :type/Text
   :SQL_LONGVARCHAR         :type/Text
   :SQL_CHAR                :type/Text
   :TIMESTAMP               :type/DateTime
   :DATE                    :type/DateTime
   :SQL_BOOLEAN             :type/Boolean
   (keyword "bit varying")                :type/*
   (keyword "character varying")          :type/Text
   (keyword "double precision")           :type/Float
   (keyword "time with time zone")        :type/Time
   (keyword "time without time zone")     :type/Time
   (keyword "timestamp with timezone")    :type/DateTime
   (keyword "timestamp without timezone") :type/DateTime})

(defn- column->special-type
  "Attempt to determine the special-type of a Field given its name and Crossdata column type."
  [column-name column-type]
  ;; this is really, really simple right now.  if its crossdata :json type then it's :type/SerializedJSON special-type
  (case column-type
    :json :type/SerializedJSON
    :inet :type/IPAddress
    nil))

(def ^:const ssl-params
  "Params to include in the JDBC connection spec for an SSL connection."
  {:ssl        false
   :sslmode    "require"
   :sslfactory "org.crossdata.ssl.NonValidatingFactory"})  ; HACK Why enable SSL if we disable certificate validation?

(def ^:const disable-ssl-params
  "Params to include in the JDBC connection spec to disable SSL."
  {:sslmode "disable"})

(defn execute-query
  "Process and run a native (raw SQL) QUERY."
  [driver {:keys [database settings ], query :native, {sql :query, params :params} :native, :as outer-query}]

  (let [sql (str
              (if (seq params)
                (unprepare/unprepare (cons sql params))
                sql))]
    (let [query (assoc query :remark  "", :query  sql, :params  nil)]
      (qprocessor/do-with-try-catch
        (fn []
          (let [db-connection (sql/db->jdbc-connection-spec database)]
            (qprocessor/do-in-transaction db-connection (partial qprocessor/run-query-with-out-remark query))))))))

(defn apply-order-by
  "Apply `order-by` clause to HONEYSQL-FORM. Default implementation of `apply-order-by` for SQL drivers."
  [_ honeysql-form {subclauses :order-by}]
  (loop [honeysql-form honeysql-form, [{:keys [field direction]} & more] subclauses]
    (let [honeysql-form (h/merge-order-by honeysql-form [(keyword (qprocessor/display_name field)) (case direction
                                                                             :ascending  :asc
                                                                             :descending :desc)])]
      (if (seq more)
        (recur honeysql-form more)
        honeysql-form))))

(defn- connection-details->spec [{ssl? :ssl, :as details-map}]
  (-> details-map
      (update :port (fn [port]
                      (if (string? port)
                        (Integer/parseInt port)
                        port)))
      ;; remove :ssl in case it's false; DB will still try (& fail) to connect if the key is there
      (dissoc :ssl)
      (merge (if ssl?
               ssl-params
               disable-ssl-params))
      (rename-keys {:dbname :db})
      dbspec/crossdata
      (sql/handle-additional-options details-map)))


(defn- unix-timestamp->timestamp [expr seconds-or-milliseconds]
  (case seconds-or-milliseconds
    :seconds      (hsql/call :to_timestamp expr)
    :milliseconds (recur (hx// expr 1000) :seconds)))

(defn- date-trunc [unit expr]
  (hsql/call unit expr))

(defn- extract    [unit expr]
  (hsql/call :extract    unit              expr))

(def ^:private extract-integer (comp hx/->integer extract))

(def ^:private ^:const one-day
  (hsql/raw "INTERVAL '1 day'"))

(defn- date [unit expr]
  (case unit
    :default         expr
    :minute          (date-trunc :minute expr)
    :minute-of-hour  (date-trunc :minute expr)
    :hour            (date-trunc :hour expr)
    :hour-of-day     (date-trunc :hour expr)
    :day             (date-trunc :day expr)
    ;; Crossdata DOW is 0 (Sun) - 6 (Sat); increment this to be consistent with Java, H2, MySQL, and Mongo (1-7)
    :day-of-week     (hx/inc (extract-integer :dow expr))
    :day-of-month    (extract-integer :day expr)
    :day-of-year     (extract-integer :doy expr)
    ;; Crossdata weeks start on Monday, so shift this date into the proper bucket and then decrement the resulting day
    :week            (date-trunc :weekofyear expr)
    :week-of-year    (date-trunc :weekofyear expr)
    :month           (date-trunc :month expr)
    :month-of-year   (date-trunc :month expr)
    :quarter         (date-trunc :quarter expr)
    :quarter-of-year (extract-integer :quarter expr)
    :year            (date-trunc :year expr)))

(defn- date-interval [unit amount]
  (hsql/raw (format "(now() + INTERVAL %d %s)" (int amount) (name unit))))

(defn- humanize-connection-error-message [message]
  (condp re-matches message
    #"^FATAL: database \".*\" does not exist$"
    (driver/connection-error-messages :database-name-incorrect)

    #"^No suitable driver found for.*$"
    (driver/connection-error-messages :invalid-hostname)

    #"^Connection refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.$"
    (driver/connection-error-messages :cannot-connect-check-host-and-port)

    #"^FATAL: role \".*\" does not exist$"
    (driver/connection-error-messages :username-incorrect)

    #"^FATAL: password authentication failed for user.*$"
    (driver/connection-error-messages :password-incorrect)

    #"^FATAL: .*$" ; all other FATAL messages: strip off the 'FATAL' part, capitalize, and add a period
    (let [[_ message] (re-matches #"^FATAL: (.*$)" message)]
      (str (s/capitalize message) \.))

    #".*" ; default
    message))

(defn- prepare-value [{value :value, {:keys [base-type]} :field}]
  (if-not value
    value
    (cond
      (isa? base-type :type/UUID)      (UUID/fromString value)
      (isa? base-type :type/IPAddress) (hx/cast :inet value)
      :else                            value)))

(defn- string-length-fn [field-key]
  (hsql/call :length (hx/cast :STRING field-key)))


(defrecord CrossdataDriver []
  clojure.lang.Named
  (getName [_] "Crossdata"))

(def CrossdataISQLDriverMixin
  "Implementations of `ISQLDriver` methods for `CrossdataDriver`."
  (merge (sql/ISQLDriverDefaultsMixin)
         {:column->base-type         (u/drop-first-arg column->base-type)
          :column->special-type      (u/drop-first-arg column->special-type)
          :connection-details->spec  (u/drop-first-arg connection-details->spec)
          :date                      (u/drop-first-arg date)
          :prepare-value             (u/drop-first-arg prepare-value)
          :set-timezone-sql          (constantly "UPDATE pg_settings SET setting = ? WHERE name ILIKE 'timezone';")
          :string-length-fn          (u/drop-first-arg string-length-fn)
          :apply-order-by             apply-order-by
          :unix-timestamp->timestamp (u/drop-first-arg unix-timestamp->timestamp)}))

(u/strict-extend CrossdataDriver
                 driver/IDriver
                 (merge (sql/IDriverSQLDefaultsMixin)
         {:date-interval                     (u/drop-first-arg date-interval)
          :details-fields                    (constantly [{:name         "host"
                                                           :display-name "Host"
                                                           :default      "localhost"}
                                                          {:name         "port"
                                                           :display-name "Port"
                                                           :type         :integer
                                                           :default      13422}
                                                          {:name         "dbname"
                                                           :display-name "Use a secure connection (SSL)?"
                                                           :default      false
                                                           :type         :boolean}
                                                          {:name         "user"
                                                           :display-name "Database username"
                                                           :placeholder  "What username do you use to login to the database?"
                                                           :required     true}
                                                          {:name         "ssl"
                                                           :display-name "Use a secure connection (SSL)?"
                                                           :type         :boolean
                                                           :default      false}
                                                          {:name         "additional-options"
                                                           :display-name "Additional JDBC connection string options"
                                                           :placeholder  "prepareThreshold=0"}])
          :execute-query            execute-query
          :humanize-connection-error-message (u/drop-first-arg humanize-connection-error-message)})


                 sql/ISQLDriver CrossdataISQLDriverMixin)
:crossdata
(driver/register-driver! :crossdata (CrossdataDriver.))
