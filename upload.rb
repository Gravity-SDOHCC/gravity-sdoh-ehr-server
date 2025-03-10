require 'fhir_client'

path = File.join(Dir.pwd, 'src', 'main', 'resources', 'fhir_resources')
files = Dir.glob(path + "/*.json")
client = FHIR::Client.new('https://sdoh-ehr-server.victoriousbay-86ce63e0.southcentralus.azurecontainerapps.io/fhir')
# client = FHIR::Client.new('http://localhost:8080/fhir')
files.each do |file|
  contents = File.read(file)
  resource = FHIR.from_contents(contents)
  client.update(resource, resource.id)
end
