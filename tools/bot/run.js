'use strict'
/**
 * Scenario runner for the modServer development server.
 *
 * Connects a bot (mineflayer) to the server, runs a list of steps and verifies
 * the game answers. Returns exit code 0 if everything passes.
 *
 * Usage:
 *   node run.js --scenario scenarios/smoke.json
 *   node run.js --probe            # dumps the world state as JSON
 *
 * Scenario format (JSON):
 *   [
 *     { "command": "/modserver selftest", "expect": ["PASS"], "timeout": 15000 },
 *     { "chat": "hola", "wait": 500 },
 *     { "wait": 1000 }
 *   ]
 */

const fs = require('fs')
const path = require('path')
const mineflayer = require('mineflayer')

// ---------------------------------------------------------------- arguments
function parseArgs (argv) {
  const args = { host: '127.0.0.1', port: 25565, username: 'AgentBot', scenario: null, probe: false, timeout: 120000, version: '1.21.1' }
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    const next = () => argv[++i]
    if (a === '--host') args.host = next()
    else if (a === '--port') args.port = parseInt(next(), 10)
    else if (a === '--user' || a === '--username') args.username = next()
    else if (a === '--version') args.version = next()
    else if (a === '--scenario') args.scenario = next()
    else if (a === '--probe') args.probe = true
    else if (a === '--timeout') args.timeout = parseInt(next(), 10)
    else if (a === '--help' || a === '-h') {
      console.log('Usage: node run.js [--host H] [--port P] [--user U] [--scenario file] [--probe]')
      process.exit(0)
    }
  }
  return args
}

// ---------------------------------------------------------------- utilities
const sleep = ms => new Promise(r => setTimeout(r, ms))

function waitForEvent (emitter, event, timeout, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      emitter.removeListener(event, onEvent)
      reject(new Error(`Timeout waiting for the event "${event}"${label ? ' (' + label + ')' : ''}`))
    }, timeout)
    function onEvent (...args) {
      clearTimeout(timer)
      resolve(args)
    }
    emitter.once(event, onEvent)
  })
}

/**
 * Runs a chat command and returns the game messages that answered it.
 */
function runCommand (bot, command, expect = [], timeout = 15000) {
  return new Promise((resolve, reject) => {
    const collected = []
    const onMessage = msg => {
      const text = msg.toString()
      collected.push(text)
      if (expect.length === 0) return
      if (expect.every(e => collected.some(c => c.includes(e)))) {
        clearTimeout(timer)
        bot.removeListener('message', onMessage)
        resolve(collected)
      }
    }
    const timer = setTimeout(() => {
      bot.removeListener('message', onMessage)
      const err = new Error(`Timeout on command "${command}"`)
      err.collected = collected
      err.expected = expect
      reject(err)
    }, timeout)
    bot.on('message', onMessage)
    bot.chat(command)
    if (expect.length === 0) {
      // No assertion: wait a bit and return what was captured.
      setTimeout(() => {
        clearTimeout(timer)
        bot.removeListener('message', onMessage)
        resolve(collected)
      }, Math.min(1500, timeout))
    }
  })
}

// ----------------------------------------------------------------- scenario
async function runScenario (bot, steps) {
  const results = []
  for (const step of steps) {
    const started = Date.now()
    try {
      if (step.command) {
        const expect = step.expect || []
        const collected = await runCommand(bot, step.command, expect, step.timeout || 15000)
        results.push({
          step: step.command,
          ok: true,
          ms: Date.now() - started,
          output: collected.slice(-8)
        })
        console.log(`  ✔ ${step.command} (${Date.now() - started}ms)`)
        for (const line of collected.slice(-4)) console.log(`      ${line}`)
      } else if (step.chat) {
        bot.chat(step.chat)
        await sleep(step.wait || 500)
        results.push({ step: `chat: ${step.chat}`, ok: true, ms: Date.now() - started })
        console.log(`  ✔ chat: ${step.chat}`)
      } else if (step.wait) {
        await sleep(step.wait)
        results.push({ step: `wait ${step.wait}ms`, ok: true, ms: Date.now() - started })
        console.log(`  ✔ wait ${step.wait}ms`)
      } else {
        results.push({ step: JSON.stringify(step), ok: true, ms: 0, skipped: true })
      }
    } catch (err) {
      console.error(`  ✘ ${step.command || JSON.stringify(step)} -> ${err.message}`)
      if (err.collected) {
        console.error('    received output:')
        for (const line of err.collected.slice(-10)) console.error(`      ${line}`)
      }
      results.push({ step: step.command || JSON.stringify(step), ok: false, error: err.message, ms: Date.now() - started })
      if (!step.continueOnError) break
    }
  }
  return results
}

// --------------------------------------------------------------------- probe
function probeState (bot) {
  const pos = bot.entity.position
  const blocks = []
  for (let dx = -2; dx <= 2; dx++) {
    for (let dy = -1; dy <= 2; dy++) {
      for (let dz = -2; dz <= 2; dz++) {
        const b = bot.blockAt(pos.offset(dx, dy, dz))
        if (b && b.name !== 'air') blocks.push({ pos: [dx, dy, dz], name: b.name })
      }
    }
  }
  return {
    username: bot.username,
    version: bot.version,
    position: { x: +pos.x.toFixed(2), y: +pos.y.toFixed(2), z: +pos.z.toFixed(2) },
    health: bot.health,
    food: bot.food,
    gamemode: bot.game?.gameMode,
    dimension: bot.game?.dimension,
    nearbyBlocks: blocks,
    inventory: bot.inventory?.items().map(i => ({ name: i.name, count: i.count }))
  }
}

// ---------------------------------------------------------------------- main
async function main () {
  const args = parseArgs(process.argv)
  const bot = mineflayer.createBot({
    host: args.host,
    port: args.port,
    username: args.username,
    auth: 'offline',
    version: args.version,
    checkTimeoutInterval: 60000,
    hideErrors: false
  })

  let exitCode = 0
  try {
    await waitForEvent(bot, 'spawn', args.timeout, 'spawn')
    console.log(`✔ Bot connected as ${args.username} (version ${bot.version})`)
    // Small settle: some server messages arrive right after spawn.
    await sleep(1200)

    if (args.probe) {
      const state = probeState(bot)
      console.log(JSON.stringify(state, null, 2))
    } else {
      if (!args.scenario) {
        console.error('Missing --scenario or --probe')
        exitCode = 2
      } else {
        const file = path.resolve(args.scenario)
        const steps = JSON.parse(fs.readFileSync(file, 'utf8'))
        console.log(`Running scenario ${path.basename(file)} (${steps.length} steps):`)
        const results = await runScenario(bot, steps)
        const failed = results.filter(r => !r.ok)
        console.log('')
        console.log(`Result: ${results.length - failed.length}/${results.length} steps OK`)
        if (failed.length) {
          console.error('Failures:')
          for (const f of failed) console.error(`  - ${f.step}: ${f.error}`)
          exitCode = 1
        }
      }
    }
  } catch (err) {
    console.error('ERROR: ' + err.message)
    exitCode = 1
  } finally {
    bot.quit()
    await sleep(400)
    process.exit(exitCode)
  }
}

main()
