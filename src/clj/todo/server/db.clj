(ns todo.server.db
  "All Datomic access. Datomic never overwrites data — every transaction
  adds facts to an immutable log, and (d/db conn) is a point-in-time value
  of the whole database. That is why todo-history at the bottom of this
  file needs no extra tables, triggers, or event-sourcing framework."
  (:require [datomic.client.api :as d])
  (:import (java.util Date UUID)))

(def schema
  [{:db/ident       :user/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Public id, used in the session and the API"}
   {:db/ident       :user/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    ;; unique/value (not /identity): transacting a duplicate email throws
    ;; a conflict instead of silently merging with the existing user.
    :db/unique      :db.unique/value}
   {:db/ident       :user/password-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :todo/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :todo/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :todo/done
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}
   {:db/ident       :todo/owner
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :todo/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn connect!
  "Create (if needed) and connect to the database, ensuring schema.
  Schema transactions are idempotent, so this is safe to run at every boot.
  storage-dir :mem gives a throwaway in-memory instance (used by tests)."
  [{:keys [storage-dir db-name] :or {db-name "todo"}}]
  (let [client (d/client {:server-type :datomic-local
                          :system      db-name
                          :storage-dir storage-dir})]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data schema})
      conn)))

;;; users

(defn create-user!
  "Transacts a new user; throws on duplicate email (see :user/email above)."
  [conn {:keys [email password-hash]}]
  (let [id (UUID/randomUUID)]
    (d/transact conn {:tx-data [{:user/id            id
                                 :user/email         email
                                 :user/password-hash password-hash}]})
    {:id id :email email}))

(defn user-by-email [db email]
  (ffirst
   (d/q '[:find (pull ?u [:user/id :user/email :user/password-hash])
          :in $ ?email
          :where [?u :user/email ?email]]
        db email)))

(defn user-by-id [db id]
  (ffirst
   (d/q '[:find (pull ?u [:user/id :user/email])
          :in $ ?id
          :where [?u :user/id ?id]]
        db id)))

;;; todos

(defn- present
  "Datomic entity map -> API shape (see todo.schema/Todo)."
  [{:todo/keys [id title done created-at]}]
  {:id id :title title :done done :created-at created-at})

(def ^:private todo-pull
  [:todo/id :todo/title :todo/done :todo/created-at])

(defn todos-for-user [db user-id]
  (->> (d/q {:query '[:find (pull ?t pattern)
                      :in $ ?uid pattern
                      :where [?u :user/id ?uid]
                      [?t :todo/owner ?u]]
             :args [db user-id todo-pull]})
       (map (comp present first))
       (sort-by :created-at)
       vec))

(defn owned-todo
  "The todo, if it exists AND belongs to user-id — otherwise nil.
  Ownership is part of the query itself, so no code path can forget it."
  [db user-id todo-id]
  (some-> (ffirst
           (d/q {:query '[:find (pull ?t pattern)
                          :in $ ?uid ?tid pattern
                          :where [?t :todo/id ?tid]
                          [?t :todo/owner ?u]
                          [?u :user/id ?uid]]
                 :args [db user-id todo-id todo-pull]}))
          present))

(defn create-todo! [conn user-id title]
  (let [id (UUID/randomUUID)
        {:keys [db-after]} (d/transact conn
                                       {:tx-data [{:todo/id         id
                                                   :todo/title      title
                                                   :todo/done       false
                                                   :todo/owner      [:user/id user-id]
                                                   :todo/created-at (Date.)}]})]
    (present (d/pull db-after todo-pull [:todo/id id]))))

(defn update-todo!
  "Applies the given subset of {:title .. :done ..} and returns the fresh todo."
  [conn todo-id changes]
  (let [tx-map (cond-> {:db/id [:todo/id todo-id]}
                 (contains? changes :title) (assoc :todo/title (:title changes))
                 (contains? changes :done)  (assoc :todo/done (:done changes)))
        {:keys [db-after]} (d/transact conn {:tx-data [tx-map]})]
    (present (d/pull db-after todo-pull [:todo/id todo-id]))))

(defn delete-todo! [conn todo-id]
  (d/transact conn {:tx-data [[:db/retractEntity [:todo/id todo-id]]]})
  nil)

(defn todo-history
  "Every change ever made to a todo: [{:at inst :attribute kw :value v :op ..}].
  This is a read of Datomic's built-in history index — the app never wrote
  an audit table. If you have ever built event sourcing by hand to get an
  audit trail, this is that trail, for free, for every attribute."
  [conn todo-id]
  (let [db  (d/db conn)
        eid (ffirst (d/q '[:find ?t :in $ ?tid :where [?t :todo/id ?tid]]
                         db todo-id))]
    (when eid
      (->> (d/q '[:find ?attr ?v ?added ?at
                  :in $h $ ?e
                  :where [$h ?e ?a ?v ?tx ?added]
                  [$ ?a :db/ident ?attr]
                  [$ ?tx :db/txInstant ?at]]
                (d/history db) db eid)
           (filter (fn [[attr]] (#{:todo/title :todo/done} attr)))
           (map (fn [[attr v added at]]
                  {:at at :attribute attr :value v :op (if added :set :retract)}))
           (sort-by :at)
           vec))))

(comment
  (def a {:name "string"})
  (:nmae a)

  ())
