(ns core
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [selmer.parser :as selmer]))

(def data-dir "data")
(def output-dir "output")
(def clients-file (str data-dir "/clients.edn"))
(def invoices-file (str data-dir "/invoices.edn"))
(def settings-file (str data-dir "/settings.edn"))

(def chrome-bin "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")

(def currency-symbols
  {"ZAR" "R"
   "USD" "$"
   "INR" "\u20b9"
   "EUR" "\u20ac"
   "GBP" "\u00a3"})

;; --- helpers ---

(defn read-edn [path]
  (if (fs/exists? path)
    (try
      (edn/read-string (slurp path))
      (catch Exception e
        (println (str "Error: failed to parse " path " — " (.getMessage e)))
        (System/exit 1)))
    {}))

(defn write-edn [path data]
  (spit path (pr-str data)))

(defn next-id [m]
  (if (empty? m) 1 (inc (apply max (keys m)))))

(defn format-amount [amount currency]
  (let [sym (get currency-symbols currency currency)]
    (str sym (format "%,.2f" (double amount)))))

(defn today []
  (str (java.time.LocalDate/now)))

(defn now []
  (str (java.time.Instant/now)))

(defn plus-days [date-str n]
  (str (.plusDays (java.time.LocalDate/parse date-str) n)))

(defn read-settings []
  (let [s (read-edn settings-file)]
    (when (empty? s)
      (println "Error: settings.edn not found or empty.")
      (System/exit 1))
    s))

(defn nl->br [s]
  (when s (str/replace s "\n" "<br>")))

;; --- data access ---

(defn read-clients [] (read-edn clients-file))
(defn read-invoices [] (read-edn invoices-file))

;; --- html generation ---

(defn generate-html [invoice client settings]
  (let [currency (:currency client)
        items (:items invoice)
        total (reduce + 0 (map :amount items))
        status (:status invoice)
        net (or (:net invoice) 30)
        notes (or (:notes invoice) (:notes settings))
        paid? (= status "paid")
        payment-amt (or (:payment-amount invoice) (when paid? total) 0)
        amount-due (- total payment-amt)]
    (selmer/render (slurp "templates/invoice.html")
                   {:status       status
                    :status-upper (str/upper-case status)
                    :show-status  (not= status "ready")
                    :id           (:id invoice)
                    :issue-date   (:issue-date invoice)
                    :due-date     (:due-date invoice)
                    :net          net
                    :description  (:description invoice)
                    :from         (update settings :address nl->br)
                    :client       (update client :address nl->br)
                    :items        (map #(assoc % :formatted-amount
                                               (format-amount (:amount %) currency))
                                      items)
                    :subtotal     (format-amount total currency)
                    :payment-amount (when (> payment-amt 0) (format-amount (- payment-amt) currency))
                    :amount-due   (format-amount amount-due currency)
                    :notes        (when notes (nl->br notes))})))

;; --- pdf generation ---

(defn generate-pdf [html-path pdf-path]
  (process/shell {:err :string}
                 chrome-bin
                 "--headless"
                 "--disable-gpu"
                 "--no-pdf-header-footer"
                 (str "--print-to-pdf=" (fs/absolutize pdf-path))
                 (str "file://" (fs/absolutize html-path))))

(defn render-invoice [{:keys [invoices invoice client]}]
  (let [settings (read-settings)
        id (:id invoice)
        html-file (str output-dir "/INV-" id ".html")
        pdf-file (str output-dir "/INV-" id ".pdf")
        html (generate-html invoice client settings)
        updated (assoc invoice :html-file html-file :pdf-file pdf-file)]
    (spit html-file html)
    (generate-pdf html-file pdf-file)
    (write-edn invoices-file (assoc invoices id updated))
    (println (str "  -> " html-file))
    (println (str "  -> " pdf-file))))

;; --- commands ---

(defn init-cmd [_args]
  (fs/create-dirs data-dir)
  (if (fs/exists? settings-file)
    (println (str "Settings file already exists at " settings-file))
    (do
      (write-edn settings-file
                  {:name "Your Name"
                   :address "123 Main Street\nCity, State\nCountry - 000000"
                   :gstn "00XXXXX0000X0XX"
                   :lut "XX000000000000X"
                   :phone "+00-00000-00000"
                   :email "you@example.com"
                   :notes "Bank Details\nAccount: XXXX\nIFSC: XXXX"})
      (println (str "Sample settings created at " settings-file))
      (println "Edit this file with your details before creating invoices."))))

(defn client-add-cmd [args]
  (fs/create-dirs data-dir)
  (let [opts (cli/parse-opts args {:spec {:name {:require true}
                                          :currency {:require true}
                                          :address {:require true}
                                          :registration {}}})
        clients (read-clients)
        id (next-id clients)
        client {:id id
                :name (:name opts)
                :currency (str/upper-case (:currency opts))
                :address (:address opts)
                :registration (:registration opts)
                :created-at (now)}]
    (write-edn clients-file (assoc clients id client))
    (println (str "Client added with ID: " id))))

(def invoice-spec
  {:client {:require true :coerce :int}
   :description {}
   :item-desc {}
   :item-amount {:coerce :double}
   :status {}
   :issue-date {}
   :due-date {}
   :net {:coerce :int}
   :notes {}
   :payment-amount {:coerce :double}})

(def ^:private simple-invoice-keys
  [:status :description :net :issue-date :due-date :notes :payment-amount])

(defn apply-invoice-opts [invoice opts]
  (let [invoice (merge invoice (select-keys opts simple-invoice-keys))
        invoice (cond-> invoice
                  (and (:item-desc opts) (:item-amount opts))
                  (update :items conj {:description (:item-desc opts)
                                       :amount (:item-amount opts)})
                  (= (:status opts) "ready")
                  (assoc :ready-at (now))
                  (= (:status opts) "paid")
                  (assoc :paid-at (now)))]
    (if (and (or (:issue-date opts) (:net opts)) (not (:due-date opts)))
      (assoc invoice :due-date (plus-days (:issue-date invoice)
                                          (or (:net invoice) 30)))
      invoice)))

(defn inv-create-cmd [args]
  (fs/create-dirs output-dir)
  (let [opts (cli/parse-opts args {:spec (assoc (dissoc invoice-spec :item-desc :item-amount)
                                                :id {:coerce :int})})
        clients (read-clients)
        client-id (:client opts)]
    (when-not (get clients client-id)
      (println (str "Error: client " client-id " not found."))
      (System/exit 1))
    (let [invoices (read-invoices)
          id (or (:id opts) (next-id invoices))]
      (when (get invoices id)
        (println (str "Error: invoice " id " already exists."))
        (System/exit 1))
      (let [issue (or (:issue-date opts) (today))
            net (or (:net opts) 30)
            invoice (apply-invoice-opts
                      {:id id
                       :client-id client-id
                       :status "draft"
                       :issue-date issue
                       :due-date (plus-days issue net)
                       :net net
                       :items []
                       :created-at (now)}
                      (dissoc opts :client :issue-date :net :id))
            invoices (assoc invoices id invoice)]
        (write-edn invoices-file invoices)
        (println (str "Invoice created with ID: " id))
        (render-invoice {:invoices invoices
                         :invoice invoice
                         :client (get clients client-id)})))))

(defn inv-update-cmd [args]
  (let [opts (cli/parse-opts args {:spec (assoc invoice-spec
                                                :id {:require true :coerce :int}
                                                :client {:coerce :int})})
        invoices (read-invoices)
        id (:id opts)
        invoice (get invoices id)]
    (when-not invoice
      (println (str "Error: invoice " id " not found."))
      (System/exit 1))
    (when (= "paid" (:status invoice))
      (println "Error: cannot update a paid invoice.")
      (System/exit 1))
    (when (and (:payment-amount opts)
               (not= "paid" (:status opts)))
      (println "Error: payment amount can only be set when marking invoice as paid. Use --status paid.")
      (System/exit 1))
    (let [invoice (-> (apply-invoice-opts invoice (dissoc opts :id :client))
                      (assoc :updated-at (now)))
          clients (read-clients)
          invoices (assoc invoices id invoice)]
      (write-edn invoices-file invoices)
      (println (str "Invoice " id " updated."))
      (render-invoice {:invoices invoices
                       :invoice invoice
                       :client (get clients (:client-id invoice))}))))

(defn inv-delete-cmd [args]
  (let [opts (cli/parse-opts args {:spec {:id {:require true :coerce :int}}})
        invoices (read-invoices)
        id (:id opts)
        invoice (get invoices id)]
    (when-not invoice
      (println (str "Error: invoice " id " not found."))
      (System/exit 1))
    (when-let [html (:html-file invoice)] (fs/delete-if-exists html))
    (when-let [pdf (:pdf-file invoice)] (fs/delete-if-exists pdf))
    (write-edn invoices-file (dissoc invoices id))
    (println (str "Invoice " id " deleted."))))

(defn list-cmd [args]
  (let [what (first args)]
    (case what
      ("client" "clients")
      (let [clients (read-clients)]
        (if (empty? clients)
          (println "No clients.")
          (doseq [[id c] (sort clients)]
            (println (str "  [" id "] " (:name c) " (" (:currency c) ")")))))

      ("invoice" "invoices")
      (let [invoices (read-invoices)
            clients (read-clients)]
        (if (empty? invoices)
          (println "No invoices.")
          (doseq [[id inv] (sort invoices)]
            (let [client (get clients (:client-id inv))
                  total (reduce + 0 (map :amount (:items inv)))]
              (println (str "  [" id "] "
                            (:name client) " | "
                            (format-amount total (:currency client)) " | "
                            (:status inv) " | "
                            (:issue-date inv)))))))

      (println "Usage: bb run list [clients|invoices]"))))
