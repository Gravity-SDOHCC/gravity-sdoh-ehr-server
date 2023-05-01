require "httparty"

FHIR_SERVER = "http://localhost:8080/fhir"
$count = 0
$retry_resources = Array.new()

# Fetch all task resources
def fetch_tasks
  response = HTTParty.get("#{FHIR_SERVER}/Task")
  if response.code == 200
    bundle = JSON.parse(response.body)
    ids = bundle["entry"]&.map { |entry| entry["resource"] }.map { |resource| resource["id"] }
    ids&.each do |id|
      resonse = HTTParty.delete("#{FHIR_SERVER}/Task/#{id}?_cascade=delete")
      puts resonse.code
    end
    # puts response.
  end
end

fetch_tasks
