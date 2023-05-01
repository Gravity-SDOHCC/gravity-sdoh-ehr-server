require "zip"
require "tmpdir"
require "httparty"
require "fileutils"

FHIR_SERVER = "http://localhost:8080/fhir"
$count = 0
$retry_resources = Array.new()

def upload_ig_examples(server)
  definitions_url = "https://build.fhir.org/ig/HL7/fhir-sdoh-clinicalcare/examples.json.zip"
  definitions_data = HTTParty.get(definitions_url, verify: false)
  definitions_file = Tempfile.new
  begin
    definitions_file.write(definitions_data)
  ensure
    definitions_file.close
  end

  Zip::File.open(definitions_file.path) do |zip_file|
    zip_file.entries
      .select { |entry| entry.name.end_with? ".json" }
      .reject { |entry| entry.name.start_with? "ImplementationGuide" }
      .each do |entry|
      resource = JSON.parse(entry.get_input_stream.read, symbolize_names: true)
      response = upload_resource(resource, server)
    end
  end
ensure
  definitions_file.unlink
end

def upload_resource(resource, server)
  resource_type = resource[:resourceType]
  id = resource[:id]
  puts "Uploading #{resource_type}/#{id}"
  begin
    response = HTTParty.put(
      "#{server}/#{resource_type}/#{id}",
      body: resource.to_json,
      headers: { 'Content-Type': "application/json" },
    )
    if response.code != 201 && response.code != 200
      puts " ... ERROR: #{response.code}"
      $retry_resources.push(resource)
    else
      $count += 1
    end
  rescue StandardError
    puts " ... ERROR: Unable to upload resource. Make sure the server is accessible."
    $retry_resources.push(resource)
  end
end

def upload_retry_resources(server)
  resources = $retry_resources
  $retry_resources = Array.new()
  resources.each do |resource|
    upload_resource(resource, server)
  end
end

if ARGV.length == 0
  server = FHIR_SERVER
elsif ARGV.length == 1
  server = FHIR_SERVER
  data_dir = ARGV[0]
else
  server = ARGV[1]
  data_dir = ARGV[0]
end

puts "PUTTING IG example resources to #{server}"
upload_ig_examples(server)
puts "Uploaded #{$count} resources to #{server}"
puts "Retyring #{$retry_resources.length} resources..."
upload_retry_resources(server)
puts "#{$retry_resources.length} still failed."
puts "DONE"
