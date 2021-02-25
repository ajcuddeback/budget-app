async function addExpenseHandler(event) {
    const name = document.querySelector('#expenseName').value;
    const amount = document.querySelector('#expenseAmount').value;

    const response = await fetch('/api/bills', {
        method: 'POST',
        body: JSON.stringify({
            name,
            amount
        }),
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        document.location.reload();
    } else {
        alert(response.statusText)
    }
};

async function getExpenseHandler(event) {

    const id = window.location.toString().split('/')[
        window.location.toString().split('/').length - 1
    ];

    const response = await fetch(`/api/users/${id}`, {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        response.json().then(text => {
            const expenseDiv = document.querySelector('.expense-output-wrapper');

            const billArr = text.bills
            billArr.forEach(bill => {
                let expenseItemsDiv = document.createElement('div');
                let billName = document.createElement('p');
                let billAmount = document.createElement('p');

                billName.innerHTML = bill.name;
                billAmount.innerHTML = bill.amount;

                expenseItemsDiv.classList.add('expense-items');

                expenseItemsDiv.append(billName);
                expenseItemsDiv.append(billAmount);
                expenseDiv.append(expenseItemsDiv);


            })

        })
    } else {
        alert(response.statusText)
    }
};

getExpenseHandler();


document.querySelector('.add-expense').addEventListener('click', addExpenseHandler);