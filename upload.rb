require 'fhir_client'

path = File.join(Dir.pwd, 'fhir_resources')
files = Dir.glob(path + "/*.json")
client = FHIR::Client.new('https://gravity-ehr-server.herokuapp.com/fhir')
files.each do |file|
  contents = File.read(file)
  resource = FHIR.from_contents(contents)
  client.update(resource, resource.id)
end
