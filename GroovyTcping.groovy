//=============================================================================
// META
appName    = 'GroovyTcping'
appDescr   = 'Checks TCP network connectivity for a list of hosts+ports'
appVersion = '1.0.0'
appAuthor  = 'Lukas Kästner'
appLicense = 'SPDX: MIT'

//=============================================================================
// INIT

// grab dependencies
@Grab(group='ch.qos.logback', module='logback-classic', version='1.2.3')
@Grab(group='org.apache.commons', module='commons-csv', version='1.7')

// import classes
import groovy.transform.Field
import org.apache.commons.csv.CSVFormat
import java.nio.file.Path
import java.nio.file.Paths

// global fields
@Field final logger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
@Field final cli = new groovy.cli.commons.CliBuilder()
@Field options = null
@Field final socketAddresses = []


//=============================================================================
// LOGGING
def configureLogging() {
	final patternEncoder = new ch.qos.logback.classic.encoder.PatternLayoutEncoder()
	patternEncoder.context = logger.loggerContext
	patternEncoder.pattern = '%-5level - %message%n'
	patternEncoder.start()

	logger.getAppender('console').encoder = patternEncoder
	logger.level = ch.qos.logback.classic.Level.INFO

	logger.info("${appName}")
	logger.info("Run this program with argument -h to view help and usage-information.")
}


//=============================================================================
// CLI ARGUMENTS
def configureCLI() {
	// read and parse CLI arguments, thus specifying the public API
	cli.with {
		h  longOpt: 'help', type: boolean, 'display help and usage-instructions'
		d  longOpt: 'debug', type: boolean, 'increase logging-verbosity and disable parallel file-processing'
		i  longOpt: 'inFile', convert: { Paths.get(it) }, defaultValue: 'in.csv', 'absolute or relative path of input CSV file'
		o  longOpt: 'outFile', convert: { Paths.get(it) }, defaultValue: 'out.csv', 'absolute or relative path of output CSV file'
		f  longOpt: 'csvFormat', convert: { CSVFormat.valueOf(it) }, defaultValue: 'Default', 'specify format of CSV to read and write. See: org.apache.commons.csv.CSVFormat.Predefined' 
		t  longOpt: 'timeoutMillis', type: int, defaultValue: '1000', 'timeout in milliseconds for ping and socket-test'
	}
	options = cli.parse(args)

	// abort if options could not be parsed
	if (!options) {
		System.exit(1)
	}

	// if requested, display help-text and exit
	if (options.help) {
		cli.usage()
		System.exit(0)
	}
	
	// if requested, enable debugging and print basic debugging information
	if (options.debug) {
		logger.level = ch.qos.logback.classic.Level.DEBUG
		logger.debug("Debugging is enabled")
		logger.debug("Date: ${java.time.LocalDateTime.now()}")
		logger.debug("Runtime: Groovy ${GroovySystem.version} on JDK ${System.properties['java.version']}")
	}
}


//=============================================================================
// SCRIPT
configureLogging()
configureCLI()
parseCSV()
testAllSocketAddresses()
writeCSV()
logger.info("END")


//=============================================================================
// METHODS

// parse SocketAddresses from CSV-file
def parseCSV() {

	// define reader and parse CSV-file
	inReader = new java.io.FileReader(options.inFile.toFile());
	csvRecords = options.csvFormat
		.withFirstRecordAsHeader()
		.withIgnoreHeaderCase()
		.withIgnoreEmptyLines()
		.withIgnoreSurroundingSpaces()
		.withCommentMarker('#' as char)
		.parse(inReader)
	
	// iterate over all CSV-records and parse to list of SocketAddresses
	csvRecords.each{ record -> 
		description = record.get('Description')
		hostname = record.get('Hostname')
		
		// if CSV-record specifies multiple port-numbers (separated by +), create a SocketAddress for each
		port = record.get('Port')
		if (port.contains('+')) {
			port.split('\\+').each{ 
				addSocket(description, hostname, it as int)
			}
		} else {
			addSocket(description, hostname, port as int)
		}
	}
}

// add a SocketAddress (one host with one port) to the list of SocketAddresses
def addSocket(String description = '', String hostname, Integer port) {
	newSocketAddress = [hostname: hostname, port: port, description: description, ipaddr: '?']
	logger.debug("adding new SocketAddress: ${newSocketAddress}")
	socketAddresses << newSocketAddress
}

// test all SocketAddresses
def testAllSocketAddresses() {
	numberOfThreads = options.debug ? 1 : Math.min(socketAddresses.size(), Runtime.getRuntime().availableProcessors())
	logger.info("Processing ${socketAddresses.size()} SocketAddresses using ${numberOfThreads} parallel threads.")
	groovyx.gpars.GParsPool.withPool(numberOfThreads) {
		socketAddresses.eachParallel{ testSocketAddress(it) }
	}
}

// check if the provided string is an IPv4 address
boolean isLiteralIpAddress(inString) {
	isIpV4address = /^((0|1\d?\d?|2[0-4]?\d?|25[0-5]?|[3-9]\d?)\.){3}(0|1\d?\d?|2[0-4]?\d?|25[0-5]?|[3-9]\d?)$/
	isIpV6address = /^((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)::((?:[0-9A-Fa-f]{1,4}(?::[0-9A-Fa-f]{1,4})*)?)$/
	return inString ==~ isIpV4address || inString ==~ isIpV6address
}

// test one SocketAddress (host-port-combination)
def testSocketAddress(socketAddress) {
	logger.debug("Testing: ${socketAddress}")
	
	// initialize test-status
	socketAddress.resultDns    = 'not-tested'
	socketAddress.resultPing   = 'not-tested'
	socketAddress.resultSocket = 'not-tested'
	
	try {
		// 1. test DNS
		socketAddress.resultDns = 'failed'
		final inetAddress = java.net.InetAddress.getByName(socketAddress.hostname);
		socketAddress.ipaddr = inetAddress.hostAddress
		if (isLiteralIpAddress(socketAddress.hostname)) {
			socketAddress.resultDns = 'skipped'
		} else {
			socketAddress.resultDns = 'success'
		}
	
		// 2. test Ping (ICMP Echo or TCP-7 Echo)
		socketAddress.resultPing = 'failed'
		if (inetAddress.isReachable(options.timeoutMillis)) {
			socketAddress.resultPing = 'success'
		}
	
		// 3. test Socket
		socketAddress.resultSocket = 'failed'
		final inetSocketAddress = new java.net.InetSocketAddress(inetAddress, socketAddress.port)
		final socket = new java.net.Socket()
		socket.connect(inetSocketAddress, options.timeoutMillis)
		socket.close()
		socketAddress.resultSocket = 'success'
	
	} catch (e) {
		logger.debug("Error while testing ${socketAddress}", e)
	
	} finally {
		// print summary:
		if (socketAddress.resultSocket == 'success') {
			socketAddress.resultOverall = 'Socket-OK'
		} else if (socketAddress.resultPing == 'success') {
			socketAddress.resultOverall = 'Ping-Only'
		} else if (socketAddress.resultDns == 'success') {
			socketAddress.resultOverall = 'DNS-only'
		} else if (socketAddress.resultDns == 'failed') {
			socketAddress.resultOverall = 'No-DNS'
		} else {
			socketAddress.resultOverall = 'No-Conn'
		}
		logger.info("${socketAddress.resultOverall.toUpperCase().padRight(9)} ${socketAddress.description} - ${socketAddress.hostname}:${socketAddress.port} (${socketAddress.ipaddr})")
	}
}

// write results to CSV
def writeCSV() {

	// define CsvPrinter
	csvPrinter = options.csvFormat
		.withAutoFlush(true)
		.withHeader(
			'Description',
			'Hostname',
			'Port',
			'IP-Address',
			'Result-Overall',
			'Result-DNS',
			'Result-Ping',
			'Result-Socket')
		.print(options.outFile, java.nio.charset.Charset.defaultCharset())
	
	// print each SockedAddress
	socketAddresses.each{ sa -> 
		csvPrinter.printRecord(
			sa.description,
			sa.hostname,
			sa.port,
			sa.ipaddr,
			sa.resultOverall,
			sa.resultDns,
			sa.resultPing,
			sa.resultSocket);
	}
	csvPrinter.close()
}