// filebot -script MissingEpisodes.groovy /path/to/media/ "MyTVShows.csv" > MissingEpisodes.csv
// filebot -script /home/woofer/Filebot/MissingEpisodes.groovy /Disk2/TV\ Shows/ "/home/woofer/Filebot/MyTVShows.csv" --def gmail=ericsson.testlaptop:docklands --def mailto=guy.gangemi@gmail.com

/*
 * Assumes directory structure is /path/to/media/TV Shows/Name/Season N/file.ext
 * "MyTVShows.csv" will be created if it doesn't exist. New found TV shows will be added automatically
 * MyTVShows.csv holds list of seasons to skip. See below for format
 * That TV Show,0,1,2	<- Will scrape for "That TV Show" but ignore seasons 0, 1 and 2
 * The Wired			<- Will scrape all seasons
 * Mad Med,-1			<- Will not scrape for at all
 */
def pathToMedia = args[0]
def MyTVShowsFile = args[1]

def today = new Date().clearTime()

// enable/disable features as specified via --def parameters
def gmail = tryQuietly{ gmail.split(':', 2) }
def forecastSplit = tryQuietly { forecastSplit.toInteger() } ?: 7
def torrentSite = tryQuietly { torrentSite } ?: "http://kat.ph/usearch/"
def debugFlag = tryQuietly{ debug } ?: 0

class episodeDB {
	static toIgnore = []
	def episodeList = []
	
	static showsToGet() {
		def shows = []
		toIgnore.each {
			if ( it[1].disjoint( [ -1] )) {
				shows << it[0]
			}
		}
		return shows
	}
	
	static showsToIgnore() {
		def shows = []
		toIgnore.each {
			if ( it[1].intersect( [ -1] )) {
				shows << it[0]
			}
		}
		return shows
	}
		
	static addToIgnore( String TVShowName, ArrayList seasons){
		toIgnore << [TVShowName, seasons]
	}
	
	def addEpisode( String TVShowName, String EpName, int seasonNo, int episodeNo, Date airDate){
		def toIgnoreIndex = toIgnore.collect{ it[0] }.indexOf(TVShowName)
		
		if ( toIgnoreIndex == -1 ) {
			addToIgnore( TVShowName, [] )
		} else if ( !toIgnore[toIgnoreIndex][1].disjoint( [ -1, seasonNo] )) {
			return
		}
		
		episodeList << [TVShowName, EpName, seasonNo, episodeNo, airDate]		
	}
	
	//Returns episodes in order from date A to date B
	def getEpisodes(Date A, Date B) { 
		def sorted = []
		
		episodeList.each {
			if( ( A <= it[4] ) && ( it[4] < B ) ) {
				sorted << it
			}
		}		
		
		return sorted.sort{a, b -> a[4].equals(b[4]) ? 0 : a[4] < b[4] ? -1 : 1}
	}
	
	//Remove episodes which match TV show name, season and episode number. Ignores episode name
	def subtract(episodeDB A) { 
		def resultant = new episodeDB()
		
		episodeList.each {
			if ( !A.episodeList.collect{ it[0,2,3] }.contains( it[0,2,3] ) ) {
				resultant.addEpisode( it[0], it[1], it[2], it[3], it[4])
			}
		}
	
		return resultant
	}
}

myEpisodes = new episodeDB()
scrapedEpisodes = new episodeDB()
myMissingEpisodes = new episodeDB()

MyTVShowsFile.createNewFile()

// Reads in file with shows and what to ignore
if ( debugFlag ) println "\n*****Reading MyTVShowsFile"
MyTVShowsFile.eachLine{ line ->
	def splitLine = line.split(',')
	def seasons = []
	def seriesName = detectSeriesName("/${splitLine[0]}") //Trick detectSeriesName into working on text by making it look like a directory
	if ( splitLine.size() != 1 ) {
		splitLine[1..-1].each {
			seasons << it.toInteger()
		}
	}
	
	episodeDB.addToIgnore( seriesName, seasons )
	if ( debugFlag ) println "$seriesName : $seasons"
}

// Find every TVShow folder and record episodes
if ( debugFlag ) println "\n*****Lets look for TV shows in $pathToMedia"
pathToMedia.listFiles{it}.each{ foundFolder ->
	if ( episodeDB.showsToIgnore().contains( foundFolder.getName() ) ) {
	} else {
		foundFolder.getFiles{ it.isVideo() }.each {
			try {
				def seriesName = detectSeriesName(it)
				def sxe = parseEpisodeNumber(it)
				myEpisodes.addEpisode( seriesName, null, sxe.season, sxe.episode, null)
				if ( debugFlag ) println "$it : Found Folder \n $seriesName $sxe : Matched too this"
			} catch(e) {
				println "Error processing: $it"
			}
		}
	}
}

// Scrape info for the shows we want
if ( debugFlag ) println "\n*****Lets scrape the episode lists for the shows we want"
episodeDB.showsToGet().each{
	if ( debugFlag ) println "Scraping: $it"
	fetchEpisodeList( query:it, format:'/{n}/{s00e00} - {t}>{airdate}<', db:'TheTVDB').each{
		Date airDate = null
		
		def seriesName = detectSeriesName(it)
		def sxe = parseEpisodeNumber(it)		
		def splitName = it =~ /\/.+\/S\d+E\d+\s-\s(.*)>(.*)</
		
		if ( splitName[0][2] ) {
			airDate = Date.parse( "yyyy-MM-dd", splitName[0][2])
		}
		
		if ( debugFlag ) println "    Scarped Episode: $seriesName ${splitName[0][1]} $sxe $airDate"
		
		scrapedEpisodes.addEpisode( seriesName, splitName[0][1], sxe.season, sxe.episode, airDate )
	}
}

// Create an EpisodeDB of the missing episodes  
myMissingEpisodes = scrapedEpisodes.subtract(myEpisodes)

// Overwrite MyTVShowsFile
//def ignoreMC = [
//	compare: {a, b -> a[0].equals(b[0]) ? 0 : a[0] < b[0] ? -1 : 1 }
//] as Comparator

MyTVShowsFile.write("")
episodeDB.toIgnore.sort{a, b -> a[0].equals(b[0]) ? 0 : a[0] < b[0] ? -1 : 1 }.each {
	MyTVShowsFile.append( "${it.flatten().join(',')}\n" )
}

//Present Results
println "\n******Missing Episodes******"
println "\nAired"
myMissingEpisodes.getEpisodes( new Date(0), today).each{
	println it[4].format( 'yyyy-MM-dd' ) + " >> " + "S" + it[2].toString().padLeft(2,'0') + "E" + it[3].toString().padLeft(2,'0') + " " + it[0] + " - " + it[1] 
}
println "\nSoon"
myMissingEpisodes.getEpisodes( today, today + forecastSplit).each{
	println it[4].format( 'yyyy-MM-dd' ) + " >> " + "S" + it[2].toString().padLeft(2,'0') + "E" + it[3].toString().padLeft(2,'0') + " " + it[0] + " - " + it[1] 
}
println "\nLater"
myMissingEpisodes.getEpisodes( today + forecastSplit, today + 3650).each{
	println it[4].format( 'yyyy-MM-dd' ) + " >> " + "S" + it[2].toString().padLeft(2,'0') + "E" + it[3].toString().padLeft(2,'0') + " " + it[0] + " - " + it[1] 
}
println "\nNo Date"
myMissingEpisodes.getEpisodes( null, new Date(0)).each{
	println "          " + " >> " + "S" + it[2].toString().padLeft(2,'0') + "E" + it[3].toString().padLeft(2,'0') + " " + it[0] + " - " + it[1] 
}

if (gmail) {
	// ant/mail utility
	include('fn:lib/ant')
	
	// send html mail
	def emailTitle = "Upcoming Episodes"
	
	sendGmail(
		subject: "[FileBot] ${emailTitle}",
		message: XML {
			html {
				body {
					p( "Here's what you've missed..." )
					table {
						myMissingEpisodes.getEpisodes( new Date(0), today).each{
							def link = torrentSite + it[0] + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0')
							def name = it[0] + ' - ' + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0') + ' - ' + it[1]
							def airdate = it[4].format( 'yyyy-MM-dd' )
							tr {
								td(airdate)
								td{
									a(href: link, name)
								}
							}
						}
					}
					p( "Here's everything that's coming up in the next $forecastSplit days..." )
					table {
						myMissingEpisodes.getEpisodes( today, today + forecastSplit).each{
							def link = torrentSite + it[0] + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0')
							def name = it[0] + ' - ' + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0') + ' - ' + it[1]
							def airdate = it[4].format( 'yyyy-MM-dd' )
							tr {
								td(airdate)
								td{
									a(href: link, name)
								}
							}
						}
					}
					p( "Here's a peek at what's coming up after that..." )
					table {
						def uniqueList = []
						myMissingEpisodes.getEpisodes( today + forecastSplit, today + 3650).each{
							def link = torrentSite + it[0] + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0')
							def name = it[0] + ' - ' + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0') + ' - ' + it[1]
							def airdate = it[4].format( 'yyyy-MM-dd' )
							if ( !uniqueList.contains( it[0] ) ) {
								uniqueList << it[0]
								tr {
									td(airdate)
									td{
										a(href: link, name)
									}
								}
							}
						}
					}
					p( "This lot are missing dates..." )
					table {
						myMissingEpisodes.getEpisodes(  null, new Date(0)).each{
							def link = torrentSite + it[0] + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0')
							def name = it[0] + ' - ' + ' S' + it[2].toString().padLeft(2,'0') + 'E' + it[3].toString().padLeft(2,'0') + ' - ' + it[1]
							tr {
								td( "          " )
								td{
									a(href: link, name)
								}
							}
						}
					}
				}
			}
		},
		messagemimetype: 'text/html',
		to: tryQuietly{ mailto } ?: gmail[0] + '@gmail.com', // mail to self by default
		user: gmail[0], password: gmail[1]
	)
}
