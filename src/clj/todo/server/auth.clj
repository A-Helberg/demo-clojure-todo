(ns todo.server.auth
  "Password hashing and the session guard. Sessions live in an encrypted,
  http-only cookie (SameSite=Lax), so the API stays stateless server-side."
  (:require [buddy.hashers :as hashers]))

(defn hash-password [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn verify-password [password hash]
  (:valid (hashers/verify password hash)))

(defn session-store-key
  "Ring's cookie store encrypts with AES-128 — the key must be 16 bytes."
  ^bytes [secret]
  (let [bs (.getBytes ^String secret "UTF-8")]
    (when (< (alength bs) 16)
      (throw (ex-info "session secret must be at least 16 characters"
                      {:length (alength bs)})))
    (byte-array (take 16 bs))))

(defn wrap-require-user
  "401 unless the session carries a :user-id (set by login/register).
  Puts the id on the request so handlers never read the session directly."
  [handler]
  (fn [request]
    (if-let [user-id (get-in request [:session :user-id])]
      (handler (assoc request :user-id user-id))
      {:status 401 :body {:error "authentication required"}})))
