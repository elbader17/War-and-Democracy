'use strict'
/**
 * Bot that founds a nation and automatically claims every chunk it moves into.
 *
 *   node tools/bot/claimterritory.js --nation Testland --duration 120
 *
 * It combines with RCON teleports (tools/console.sh "tp AgentBot x y z"):
 * every time the bot changes chunk, it claims it.
 */
const mineflayer = require('mineflayer')

function parseArgs (argv) {
  const args = { host: '127.0.0.1', port: 25565, username: 'AgentBot', nation: 'Testland', duration: 60, color: null }
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    if (a === '--host') args.host = next()
    else if (a === '--port') args.port = parseInt(next(), 10)
    else if (a === '--user' || a === '--username') args.username = next()
    else if (a === '--nation') args.nation = next()
    else if (a === '--duration') args.duration = parseInt(next(), 10)
    else if (a === '--color') args.color = next()
  }
  return args
}

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function once (emitter, event) {
  return new Promise(resolve => emitter.once(event, resolve))
}

async function main () {
  const args = parseArgs(process.argv)

  const stamp = () => new Date().toISOString().substring(11, 19)
  const log = (...args) => console.log(`[${stamp()}]`, ...args)

  const bot = mineflayer.createBot({
    host: args.host,
    port: args.port,
    username: args.username,
    auth: 'offline',
    version: '1.21.1'
  })

  await once(bot, 'spawn')
  log(`connected as ${bot.username}`)

  // The server chat is printed: it is the only way to see why a command
  // (/nacion crear, /nacion claim) fails without checking the server log.
  bot.on('message', (msg) => {
    const text = msg.toString()
    if (text.includes('[nacion]')) log(`server: ${text}`)
  })

  const create = args.color
    ? `/nacion crear ${args.nation} ${args.color}`
    : `/nacion crear ${args.nation}`
  bot.chat(create)
  await sleep(3000)

  let lastChunk = null
  bot.on('move', () => {
    const pos = bot.entity.position
    const key = `${Math.floor(pos.x / 16)},${Math.floor(pos.z / 16)}`
    if (key !== lastChunk) {
      lastChunk = key
      bot.chat('/nacion claim')
      log(`claiming chunk ${key}`)
    }
  })

  log(`ready for ${args.duration}s; teleport the bot to get more chunks`)
  await sleep(args.duration * 1000)
  bot.quit()
  await sleep(300)
  process.exit(0)
}
main().catch(error => {
  console.error('ERROR:', error.message)
  process.exit(1)
})
