package com.example.vulnspring;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.Scanner;

import javax.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class WebController {

	@Autowired
	JdbcTemplate jdbcTemplate;

	private static final Logger logger = LoggerFactory.getLogger(WebController.class);

	@GetMapping(value= {"/", "/home"})
	public String home(Model model, HttpSession session) {
		model.addAttribute("username", session.getAttribute("username"));
		return "home";
	}
	

	@GetMapping("/login")
	public String login(Model model) {
		return "login";
	}

	@PostMapping("/login")
	public String login(HttpSession session, @RequestParam(name = "username", required = true) String username,
			@RequestParam(name = "password", required = true) String password, Model model) {
		if (loginSuccess(username, password)) {
			logger.debug(username + ":" + password); // Issue - password logged
			session.setAttribute("username", username);
			return "redirect:home";
		}
		return "login";
	}

	private boolean loginSuccess(String username, String password) {
		if (username == null || password == null)
			return false;
		// Issue - SQL Injection
		String query = "SELECT * FROM users WHERE USERNAME=\"" + username + "\" AND PASSWORD=\"" + password + "\"";
		Map<String, Object> result = jdbcTemplate.queryForMap(query);
		return true;
	}

	@GetMapping("/logout")
	public String logout(HttpSession session) {
		session.invalidate();
		return "redirect:home";
	}

	@GetMapping("/update")
	public String update(HttpSession session, Model model) {
		String statement = "SELECT name FROM users WHERE username=?";
		Map<String, Object> resultMap = jdbcTemplate.queryForMap(statement,
				new Object[] { session.getAttribute("username") });
		
		// Stored XSS
		model.addAttribute("name", resultMap.get("name"));
		return "update";
	}

	@PostMapping("/update")
	public String update(HttpSession session, @RequestParam(name = "newname") String newName, Model model) {
		String statement = "UPDATE users SET name = ? WHERE username = ?";
		int status = jdbcTemplate.update(statement,
				new Object[] { newName, session.getAttribute("username")});
		logger.info("Running statement: " + statement + newName + " "
				+ session.getAttribute("username"));
		logger.info("Result status for transfer is " + String.valueOf(status));
		
		if(status == 1) {
			model.addAttribute("error", "Update Failed!");
			// Reflected XSS
			model.addAttribute("name", newName);
		}	
		return "update";
	}


	@PostMapping("/checkdb")
	public String checkDB(@RequestParam(name = "dbpath") String dbpath, Model model)
			throws MalformedURLException, IOException {
		// Issue - SSRF
		String out = new Scanner(new URL(dbpath).openStream(), "UTF-8").useDelimiter("\\A").next();
		model.addAttribute("dbResponse", out);
		return "checkdb";
	}

	@GetMapping("/checkdb")
	public String checkDB() {
		return "checkdb";
	}

	@GetMapping("/transfer")
	public String transfer(HttpSession session, Model model) {
		String getBalanceStatement = "SELECT * FROM users WHERE username=?";
		Map<String, Object> balanceResultMap = jdbcTemplate.queryForMap(getBalanceStatement,
				new Object[] { session.getAttribute("username") });

		float balance = (float) balanceResultMap.get("balance");
		model.addAttribute("balance", balance);
		return "transfer";
	}

	// Issue - CSRF
	@Transactional
	@PostMapping("/transfer")
	public String transfer(HttpSession session, @RequestParam(name = "toaccount") String toAccount,
			@RequestParam(name = "amount") Float amount, Model model) {

		String fromAccount;
		Float fromAccountBalance;
		Float toAccountBalance;

		// Sanity check for transaction
		if (amount < 0) {
			model.addAttribute("error", "Negative amount value!");
			logger.info("negative amount value");
			return "transfer";
		}

		// Validate To Account
		String toAccountValidatestatement = "SELECT * FROM users WHERE accountnumber=?";
		try {
			Map<String, Object> toAccountResultMap = jdbcTemplate.queryForMap(toAccountValidatestatement,
					new Object[] { toAccount });
			toAccountBalance = (Float) toAccountResultMap.get("balance");
		} catch (EmptyResultDataAccessException e) {
			model.addAttribute("error", "Invalid To Account");
			logger.info("Invalid To Account");
			return "transfer";
		}

		// Ensure sufficient balance is available
		String fromAccountStatement = "SELECT * FROM users WHERE username=?";
		Map<String, Object> fromResultMap = jdbcTemplate.queryForMap(fromAccountStatement,
				new Object[] { session.getAttribute("username") });

		fromAccountBalance = (float) fromResultMap.get("balance");
		fromAccount = (String) fromResultMap.get("accountnumber");
		logger.info("got balance = " + String.valueOf(fromAccountBalance));

		float newBalance = fromAccountBalance - amount;
		if (newBalance < 0) {
			model.addAttribute("error", "not enough balance");
			logger.info("Not enought balance");
			return "transfer";
		}

		// Perform transaction
		String toAccStatement = "UPDATE users SET balance = ? WHERE accountnumber = ?";
		int toAccStatus = jdbcTemplate.update(toAccStatement, new Object[] { toAccountBalance + amount, toAccount });
		logger.info(
				"Running statement: " + toAccStatement + String.valueOf(toAccountBalance + amount) + " " + toAccount);
		logger.info("Result status for transfer is " + String.valueOf(toAccStatus));

		String fromAccStatement = "UPDATE users SET balance = ? WHERE accountnumber = ?";
		int fromAccStatus = jdbcTemplate.update(toAccStatement,
				new Object[] { fromAccountBalance - amount, fromAccount });
		logger.info("Running statement: " + fromAccStatement + String.valueOf(fromAccountBalance - amount) + " "
				+ fromAccount);
		logger.info("Result status for transfer is " + String.valueOf(fromAccStatus));

		if (toAccStatus == 1 && fromAccStatus == 1) {
			model.addAttribute("balance", newBalance);
			model.addAttribute("message", "Balance Transfer Successful!");
		} else {
			model.addAttribute("error", "Balance Transfer Failed!");
		}

		return "transfer";

	}

}