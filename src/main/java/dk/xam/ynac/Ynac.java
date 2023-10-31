package dk.xam.ynac;

import static java.lang.System.out;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.money.CurrencyUnit;
import javax.money.Monetary;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.javamoney.moneta.FastMoney;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dk.xam.ynac.ynab.model.SaveTransaction;
import dk.xam.ynac.ynab.model.TransactionDetail;
import io.quarkus.logging.Log;
import io.quarkus.runtime.configuration.ConfigUtils;
import dk.xam.ynac.ynab.api.BudgetsApi;
import dk.xam.ynac.ynab.api.CategoriesApi;
import dk.xam.ynac.ynab.api.TransactionsApi;
import dk.xam.ynac.ynab.model.BudgetSummary;
import dk.xam.ynac.ynab.model.Category;
import dk.xam.ynac.ynab.model.PutTransactionWrapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transaction;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "ynac", description = "You Need A Category", mixinStandardHelpOptions = true)
public class Ynac implements Runnable {

    @Inject
    Logger log;

    @Option(names = {"-b", "--budget"}, required=true)
    String budgetName;

    @Option(names = {"-m", "--mappings"}, defaultValue="mappings.yaml")
    Path mappingFile;
    

    @Inject 
    ObjectMapper mapper;
    
    @Inject @RestClient
    BudgetsApi budgetApi;

    @Inject @RestClient
    TransactionsApi txApi;

    @Inject @RestClient
    CategoriesApi catApi;

    Map<String, Category> categories = new HashMap<>();

    @ConfigProperty(name = "ynab.mappings")
    Map<String, String> mappings;

    Map<Pattern, Category> payee2category = new HashMap<>();

    private CurrencyUnit currency;
    
    Map<Pattern, Category> setupPayee(Map<String, Category> categories) {

        
        Map<String, String> result = mappings;

        return result.entrySet().stream()
            .collect(Collectors.toMap(
                entry -> Pattern.compile(entry.getKey()),
                entry -> {
                    var x = categories.get(entry.getValue());
                    if(x==null) throw new IllegalStateException("Category " + entry.getValue() + " not found!");
                    return x;
                }
            ));
    }

    @Override
    public void run() {
       
        budgetName=budgetName.trim();

        Map<String, Long> stats = new HashMap<>();


        var budgets = budgetApi.getBudgets(null);

        var budget = budgets.getData().getBudgets().stream().filter(b->b.getName().equals(budgetName)).findFirst().orElse(null);

        if(budget==null) {
            String allBudgetNames = budgets.getData().getBudgets().stream()
                .map(b -> b.getName())
                .collect(Collectors.joining(", "));
            throw new IllegalStateException("Budget '" + budgetName + "' not found in list of budgets: " + allBudgetNames);
        }

        out.println(budget);

        var cformat = budget.getCurrencyFormat();

        currency = Monetary.getCurrency(cformat.getIsoCode());
        
        log.info("Fetching Categories!");
        categories.putAll(getCategories(budget));
        categories.keySet().stream().forEach(c -> out.println(c));

        payee2category = setupPayee(categories);


      //out.println(categories);

        Set<String> unknownPayees = new HashSet<>();

        var txs = txApi.getTransactions(budget.getId().toString(),null,"uncategorized",null);

        txs.getData().getTransactions().forEach(tx -> {


            String payeeName = tx.getPayeeName();

            var findFirst = payee2category.entrySet().stream().filter(entry -> entry.getKey().matcher(payeeName).matches()).findFirst();

            if(findFirst.isPresent()) {
                
              
                Category g = findFirst.get().getValue();
                
                log.info("Want to update to " + g.getName() + " " + toStr(tx));

                stats.putIfAbsent(g.getCategoryGroupName() + "/" + g.getName(), 0L);
                var val = stats.get(g.getCategoryGroupName() + "/" + g.getName());

                stats.put(g.getCategoryGroupName()+"/"+g.getName(),val+tx.getAmount());

                var txput = createTxPut(g, tx);
                txApi.updateTransaction(budget.getId().toString(), tx.getId(), txput);
                
            } else {
                log.warn("Could not find category for " + toStr(tx));
                unknownPayees.add(tx.getPayeeName());
            }
            

        });

        log.info("Categorized amounts:");
        stats.forEach((s,v) -> {
            log.info(s + " " + FastMoney.ofMinor(currency, v/10));
        });


        log.info("Unknown payees:");
        unknownPayees.forEach(log::info);

    }

    private String toStr(TransactionDetail tx) {
        FastMoney amount = FastMoney.ofMinor(currency, tx.getAmount() / 10);

        return tx.getDate() + " '" + tx.getPayeeName() + "'" + (!tx.getMemo().equals(tx.getPayeeName())?" '" + tx.getMemo() + "'":"") + " " + amount;
    }

    private Map<String, Category> getCategories(BudgetSummary budget) {
        Map<String, Category> categories = new HashMap<>();

        catApi.getCategories(budget.getId().toString(), null).getData().getCategoryGroups().stream().forEach(cg -> {
            cg.getCategories().forEach(c -> {
               categories.put(cg.getName() + "/" + c.getName(), c);        
            });
        });

        return categories;
    }

    private PutTransactionWrapper createTxPut(Category g, TransactionDetail tx) {
        var txput = new PutTransactionWrapper();
        var newtx = new SaveTransaction();

        newtx.setCategoryId(g.getId());
        newtx.setAccountId(tx.getAccountId());
        newtx.setDate(tx.getDate());
        newtx.setAmount(tx.getAmount());
        newtx.setPayeeId(tx.getPayeeId());
        newtx.setPayeeName(tx.getPayeeName());
        newtx.setMemo(tx.getMemo());
        newtx.setCleared(SaveTransaction.ClearedEnum.fromString(tx.getCleared().value()));
        newtx.setApproved(tx.getApproved());
        newtx.setFlagColor(SaveTransaction.FlagColorEnum.fromString(tx.getFlagColor().value()));
        newtx.setImportId(tx.getImportId());

        txput.setTransaction(newtx);
        return txput;
    }

}
