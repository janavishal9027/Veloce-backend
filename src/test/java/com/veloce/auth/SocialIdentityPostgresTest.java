package com.veloce.auth;

import com.veloce.common.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="VELOCE_UI_TEST_DB_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:5432/veloce_history_verify_261115")
class SocialIdentityPostgresTest {
    DriverManagerDataSource source() {
        return new DriverManagerDataSource(System.getenv("VELOCE_UI_TEST_DB_URL"),System.getenv("VELOCE_UI_TEST_DB_USER"),
            System.getenv("VELOCE_UI_TEST_DB_PASSWORD"));
    }

    Accounts accounts(JdbcTemplate db) {
        var repository=mock(Accounts.class);
        when(repository.findById(any(UUID.class))).thenAnswer(call->db.query("SELECT id,email,display_name,password_hash FROM accounts WHERE id=?",
            (row,index)->new Account(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4)),
            call.getArgument(0,UUID.class)).stream().findFirst());
        when(repository.findByEmail(anyString())).thenAnswer(call->db.query("SELECT id,email,display_name,password_hash FROM accounts WHERE email=?",
            (row,index)->new Account(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4)),
            call.getArgument(0,String.class)).stream().findFirst());
        return repository;
    }

    Identity identity(JdbcTemplate db) {
        return new Identity(accounts(db),db,new BCryptPasswordEncoder(4),Clock.systemUTC(),
            Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test void socialConnectionsPreserveWorkspaceOwnershipAndRequireExistingAccountProof() throws Exception {
        try(var connection=source().getConnection()) {
            connection.setAutoCommit(false);
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                UUID owner=UUID.randomUUID(),workspace=UUID.randomUUID();
                String email=owner+"@example.test";
                db.update("INSERT INTO accounts(id,email,display_name,password_hash) VALUES(?,?,'Existing learner',?)",
                    owner,email,new BCryptPasswordEncoder(4).encode("fixture-password"));
                db.update("INSERT INTO workspaces(id,owner_id,name) VALUES(?,?,'Existing SQL work')",workspace,owner);
                var identity=identity(db);
                var google=new SocialTokens.Profile("GOOGLE","google-"+owner,email,"Google name");
                assertThatThrownBy(()->identity.social(google,"",null)).isInstanceOfSatisfying(Problem.class,
                    error->assertThat(error.code).isEqualTo("SOCIAL_LINK_REQUIRED"));
                assertThatThrownBy(()->identity.social(google,"incorrect-password",null)).isInstanceOfSatisfying(Problem.class,
                    error->assertThat(error.code).isEqualTo("INVALID_LINK_PASSWORD"));
                var linked=identity.social(google,"fixture-password",null);
                assertThat(linked.id()).isEqualTo(owner);
                assertThat(identity.subject(linked.accessToken())).isEqualTo(owner);
                assertThat(identity.social(google,"",null).id()).isEqualTo(owner);
                assertThat(db.queryForObject("SELECT owner_id FROM workspaces WHERE id=?",UUID.class,workspace)).isEqualTo(owner);
                var facebook=new SocialTokens.Profile("FACEBOOK","facebook-"+owner,"second-"+email,"Another name");
                assertThat(identity.social(facebook,"",owner).id()).isEqualTo(owner);
                assertThat(identity.linked(owner)).containsEntry("GOOGLE",true).containsEntry("FACEBOOK",true);
                assertThatThrownBy(()->identity.social(new SocialTokens.Profile("GOOGLE","another-google-"+owner,email,"Other"),"",owner))
                    .isInstanceOfSatisfying(Problem.class,error->assertThat(error.code).isEqualTo("SOCIAL_ALREADY_LINKED"));
                assertThatThrownBy(()->identity.social(google,"",UUID.randomUUID()))
                    .isInstanceOfSatisfying(Problem.class,error->assertThat(error.code).isEqualTo("SOCIAL_ALREADY_LINKED"));
                var changedEmail=new SocialTokens.Profile("GOOGLE",google.subject(),"renamed-"+email,"Renamed");
                assertThat(identity.social(changedEmail,"",null).id()).isEqualTo(owner);
                assertThat(identity.login(email,"fixture-password").id()).isEqualTo(owner);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test void concurrentFirstSignInsCreateOneAccountAndIndependentRefreshSessions() throws Exception {
        var source=source();
        var db=new JdbcTemplate(source);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        var identity=identity(db);
        String unique=UUID.randomUUID().toString();
        var profile=new SocialTokens.Profile("GOOGLE","concurrent-"+unique,unique+"@example.test","New learner");
        UUID created=null;
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Identity.Session> login=()->{
                start.await();
                return transaction.execute(status->identity.social(profile,"",null));
            };
            var first=executor.submit(login);
            var second=executor.submit(login);
            start.countDown();
            var one=first.get(15,TimeUnit.SECONDS);
            created=one.id();
            var two=second.get(15,TimeUnit.SECONDS);
            assertThat(two.id()).isEqualTo(one.id());
            assertThat(two.refreshToken()).isNotEqualTo(one.refreshToken());
            assertThat(db.queryForObject("SELECT count(*) FROM accounts WHERE email=?",Integer.class,profile.email())).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM social_identities WHERE provider='GOOGLE' AND subject=?",Integer.class,profile.subject())).isEqualTo(1);
        } finally {
            if(created!=null) db.update("DELETE FROM accounts WHERE id=?",created);
        }
    }
}
