$root = Join-Path $PSScriptRoot '..'

mvn clean package -DskipTests --file "$root\jobs-api\pom.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

mvn clean package -DskipTests --file "$root\jobs-consumer\pom.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
